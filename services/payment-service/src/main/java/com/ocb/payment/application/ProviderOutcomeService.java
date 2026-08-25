package com.ocb.payment.application;

import com.ocb.payment.domain.DisbursementEntryRefs;
import com.ocb.payment.domain.LedgerAccounts;
import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.TransactionType;
import com.ocb.payment.domain.TransactionStatus;
import com.ocb.payment.domain.TransactionUpdate;
import com.ocb.payment.domain.port.AuditStore;
import com.ocb.payment.domain.port.LedgerPort;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;
import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.Topics;
import com.ocb.platform.outbox.OutboxWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Traite les issues d'operations remontees par l'adaptateur operateur.
 *
 * <p>Chaque methode s'execute dans la transaction du consommateur, qui a deja enregistre
 * le message comme traite. Consequence a garder en tete : <b>lever une exception annule
 * tout</b>, y compris cet enregistrement, donc le message sera redelivre et retraite. C'est
 * exactement le comportement voulu quand rien ne permet de conclure.
 *
 * <p>La distinction structurante est celle entre un refus et une absence de reponse :
 *
 * <ul>
 *   <li>le grand livre <b>refuse</b> l'ecriture : c'est une reponse. On ne peut pas
 *       conclure a l'echec pour autant, puisque l'operateur a confirme que l'argent a
 *       bouge. La transaction passe en revue manuelle ;
 *   <li>le grand livre est <b>injoignable</b> : ce n'est pas une reponse. L'ecriture a
 *       peut-etre eu lieu. On laisse remonter l'exception pour que le message soit
 *       redelivre, et l'idempotence du grand livre fait que la retentative ne creera pas
 *       de seconde ecriture.
 * </ul>
 */
@Service
public class ProviderOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(ProviderOutcomeService.class);
    private static final String PRODUCER = "payment-service";

    private final TransactionStateService stateService;
    private final LedgerPort ledger;
    private final OutboxWriter outbox;
    private final AuditStore audit;

    public ProviderOutcomeService(TransactionStateService stateService,
                                  LedgerPort ledger,
                                  OutboxWriter outbox,
                                  AuditStore audit) {
        this.stateService = stateService;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional
    public void onAccepted(Payloads.ProviderOperationAccepted event, String correlationId) {
        stateService.apply(UUID.fromString(event.transactionId()),
                TransactionStatus.PROVIDER_ACCEPTED,
                EventTypes.PROVIDER_OPERATION_ACCEPTED,
                TransactionUpdate.providerRef(event.providerRef()));
    }

    @Transactional
    public void onSucceeded(Payloads.ProviderOperationSucceeded event, String correlationId) {
        UUID transactionId = UUID.fromString(event.transactionId());
        Money providerFee = Money.parse(event.providerFee(), event.currency());

        TransactionStateService.Applied confirmed = stateService.apply(
                transactionId, TransactionStatus.PROVIDER_CONFIRMED,
                EventTypes.PROVIDER_OPERATION_SUCCEEDED,
                TransactionUpdate.settled(event.providerRef(), providerFee));

        // Transition refusee : callback duplique, ou callback tardif sur une transaction
        // deja close. La machine a etats a tranche, le refus est journalise, il n'y a
        // rien d'autre a faire — surtout pas ecrire une seconde fois au grand livre.
        if (!confirmed.accepted()) {
            return;
        }

        TransactionStateService.Applied posting = stateService.apply(
                transactionId, TransactionStatus.POSTING,
                "LEDGER_POSTING_STARTED", TransactionUpdate.none());
        if (!posting.accepted()) {
            return;
        }

        PaymentTransaction transaction = posting.transaction();
        boolean disbursement = transaction.type() == TransactionType.DISBURSEMENT;
        String entryRef;
        try {
            entryRef = ledger.post(disbursement
                    ? settlementEntry(transaction, providerFee)
                    : collectionEntry(transaction));
        } catch (LedgerPort.LedgerUnavailableException e) {
            // On ne conclut pas. En laissant remonter, la transaction locale est annulee,
            // le message sera redelivre, et la cle d'idempotence rendra la retentative
            // inoffensive meme si l'ecriture avait en realite abouti.
            log.warn("Grand livre injoignable pour {} : retentative par redelivrance", transactionId);
            throw e;
        } catch (InvariantViolationException e) {
            // Refus definitif. L'argent a bouge chez l'operateur mais ne peut pas etre
            // comptabilise : personne ne peut trancher automatiquement.
            log.error("Ecriture refusee par le grand livre pour {} : {}", transactionId, e.getMessage());
            toManualReview(transaction, "ecriture refusee par le grand livre : " + e.code(), correlationId);
            return;
        }

        TransactionStateService.Applied completed = stateService.apply(
                transactionId, TransactionStatus.COMPLETED,
                "LEDGER_ENTRY_POSTED", TransactionUpdate.posted(entryRef));

        PaymentTransaction settled = completed.transaction();
        Object payload = disbursement
                ? new Payloads.PaymentDisbursementCompleted(
                        transactionId.toString(), settled.externalRef(),
                        settled.amount().toPlainString(), settled.amount().currencyCode(),
                        settled.platformFee().toPlainString(), providerFee.toPlainString(),
                        settled.walletAccountRef(), entryRef, settled.maskedMsisdn())
                : new Payloads.PaymentCollectionCompleted(
                        transactionId.toString(), settled.externalRef(),
                        settled.amount().toPlainString(), settled.amount().currencyCode(),
                        settled.platformFee().toPlainString(), providerFee.toPlainString(),
                        settled.walletAccountRef(), entryRef, settled.maskedMsisdn());

        outbox.append(Topics.EVT_PAYMENT, transactionId.toString(), EventEnvelope.of(
                disbursement ? EventTypes.PAYMENT_DISBURSEMENT_COMPLETED
                        : EventTypes.PAYMENT_COLLECTION_COMPLETED,
                "PaymentTransaction", transactionId.toString(), correlationId, null, PRODUCER,
                payload));

        audit.append(disbursement ? "DISBURSEMENT_COMPLETED" : "COLLECTION_COMPLETED",
                "PaymentTransaction", transactionId.toString(),
                correlationId, Map.of("ledgerEntryRef", entryRef,
                        "providerFee", providerFee.toPlainString()));
    }

    @Transactional
    public void onFailed(Payloads.ProviderOperationFailed event, String correlationId) {
        UUID transactionId = UUID.fromString(event.transactionId());

        TransactionStateService.Applied declined = stateService.apply(
                transactionId, TransactionStatus.PROVIDER_DECLINED,
                EventTypes.PROVIDER_OPERATION_FAILED,
                new TransactionUpdate(event.providerRef(), null, null,
                        event.errorCode(), event.errorMessage()));
        if (!declined.accepted()) {
            return;
        }

        // C'est ici que les deux sens divergent vraiment. Un encaissement refuse n'a rien
        // engage : aucune ecriture n'a ete passee, il echoue directement. Un decaissement
        // refuse a deja debite le portefeuille du client avant meme d'appeler l'operateur ;
        // le laisser echouer abandonnerait l'argent du client en compte de passage.
        if (declined.transaction().type() == TransactionType.DISBURSEMENT) {
            compensate(declined.transaction(), event, correlationId);
            return;
        }

        TransactionStateService.Applied failed = stateService.apply(
                transactionId, TransactionStatus.FAILED,
                "COLLECTION_FAILED", TransactionUpdate.none());
        if (!failed.accepted()) {
            return;
        }

        PaymentTransaction transaction = failed.transaction();
        outbox.append(Topics.EVT_PAYMENT, transactionId.toString(), EventEnvelope.of(
                EventTypes.PAYMENT_COLLECTION_FAILED, "PaymentTransaction",
                transactionId.toString(), correlationId, null, PRODUCER,
                new Payloads.PaymentCollectionFailed(
                        transactionId.toString(), transaction.externalRef(),
                        transaction.amount().toPlainString(), transaction.amount().currencyCode(),
                        transaction.walletAccountRef(),
                        event.errorCode(), event.errorMessage(), transaction.maskedMsisdn())));
    }

    @Transactional
    public void onUnresolved(Payloads.ProviderOperationUnresolved event, String correlationId) {
        UUID transactionId = UUID.fromString(event.transactionId());
        TransactionStateService.Applied applied = stateService.apply(
                transactionId, TransactionStatus.MANUAL_REVIEW,
                EventTypes.PROVIDER_OPERATION_UNRESOLVED, TransactionUpdate.none());
        if (applied.accepted()) {
            publishManualReview(applied.transaction(),
                    "budget de polling epuise apres %d tentatives".formatted(event.pollAttempts()),
                    correlationId);
        }
    }

    /**
     * Compensation : rendre au client des fonds deja engages.
     *
     * <p>C'est la seule etape de la plateforme qui defait un mouvement d'argent, et elle le
     * fait par <b>ajout</b>. Le grand livre etant immuable, l'ecriture d'engagement n'est
     * ni modifiee ni supprimee : une ecriture de sens inverse la designe, et les deux
     * restent visibles sur le releve du client. C'est voulu — un remboursement est un
     * fait, pas l'effacement d'un fait.
     *
     * <p>La contre-passation rend aussi nos frais de plateforme, puisqu'elle inverse les
     * trois lignes de l'engagement. Facturer la prise en charge d'un ordre que l'operateur
     * a refuse serait indefendable.
     */
    private void compensate(PaymentTransaction transaction,
                            Payloads.ProviderOperationFailed event,
                            String correlationId) {
        UUID transactionId = transaction.id();

        TransactionStateService.Applied compensating = stateService.apply(
                transactionId, TransactionStatus.COMPENSATING,
                "DISBURSEMENT_DECLINED",
                TransactionUpdate.failed(event.errorCode(), event.errorMessage()));
        if (!compensating.accepted()) {
            return;
        }

        String reversalRef;
        try {
            reversalRef = ledger.reverse(new LedgerPort.ReversalRequest(
                    // Derivee, pas retrouvee : la compensation n'a rien a relire pour
                    // savoir quelle ecriture defaire.
                    DisbursementEntryRefs.reservation(transactionId),
                    "disbursement-reversal:" + transactionId,
                    "Decaissement refuse par l'operateur (%s)".formatted(event.errorCode())));

        } catch (LedgerPort.LedgerUnavailableException e) {
            // On ne conclut pas. En laissant remonter, toute la transaction locale est
            // annulee — y compris le passage en COMPENSATING — et le message sera
            // redelivre depuis un etat coherent. La cle d'idempotence rendra la
            // retentative inoffensive meme si la contre-passation avait en realite abouti.
            log.warn("Grand livre injoignable pour compenser {} : retentative par redelivrance",
                    transactionId);
            throw e;

        } catch (InvariantViolationException e) {
            // Le grand livre refuse de contre-passer. L'argent du client est immobilise en
            // compte de passage et aucune regle automatique ne peut le liberer : c'est
            // exactement le cas ou un humain doit trancher.
            log.error("Compensation refusee par le grand livre pour {} : {}",
                    transactionId, e.getMessage());
            toManualReview(compensating.transaction(),
                    "compensation refusee par le grand livre : " + e.code(), correlationId);
            return;
        }

        TransactionStateService.Applied reversed = stateService.apply(
                transactionId, TransactionStatus.REVERSED,
                "DISBURSEMENT_REVERSED", TransactionUpdate.posted(reversalRef));
        if (!reversed.accepted()) {
            return;
        }

        PaymentTransaction t = reversed.transaction();
        outbox.append(Topics.EVT_PAYMENT, transactionId.toString(), EventEnvelope.of(
                EventTypes.PAYMENT_DISBURSEMENT_REVERSED, "PaymentTransaction",
                transactionId.toString(), correlationId, null, PRODUCER,
                new Payloads.PaymentDisbursementReversed(
                        transactionId.toString(), t.externalRef(),
                        t.amount().toPlainString(), t.amount().currencyCode(),
                        t.walletAccountRef(),
                        DisbursementEntryRefs.reservation(transactionId), reversalRef,
                        event.errorCode(), event.errorMessage(), t.maskedMsisdn())));

        audit.append("DISBURSEMENT_REVERSED", "PaymentTransaction", transactionId.toString(),
                correlationId, Map.of(
                        "reservationEntryRef", DisbursementEntryRefs.reservation(transactionId),
                        "reversalEntryRef", reversalRef,
                        "failureCode", event.errorCode()));
    }

    /**
     * Etape 2 du decaissement : livraison.
     *
     * <p>L'operateur a paye le beneficiaire depuis notre float et y a preleve sa
     * commission. Le compte de passage se solde donc, et notre float diminue du montant
     * livre augmente de cette commission :
     *
     * <pre>
     *   DR  compte de passage      montant
     *   DR  charges commission     commission operateur
     *   CR  float operateur        montant + commission operateur
     * </pre>
     *
     * <p>Une fois cette ecriture passee, plus rien ne stationne en compte de passage pour
     * cette transaction — ce qui est la definition meme d'une saga terminee.
     */
    private LedgerPort.EntryRequest settlementEntry(PaymentTransaction t, Money providerFee) {
        return new LedgerPort.EntryRequest(
                "disbursement-settlement:" + t.id(),
                DisbursementEntryRefs.settlement(t.id()),
                t.externalRef(),
                "Livraison decaissement %s %s".formatted(t.amount().toPlainString(), t.providerCode()),
                List.of(
                        LedgerPort.Line.debit(LedgerAccounts.DISBURSEMENT_SUSPENSE, t.amount()),
                        LedgerPort.Line.debit(LedgerAccounts.PROVIDER_COST, providerFee),
                        LedgerPort.Line.credit(t.providerCode().floatAccount(),
                                t.amount().add(providerFee))));
    }

    private void toManualReview(PaymentTransaction transaction, String reason, String correlationId) {
        TransactionStateService.Applied applied = stateService.apply(
                transaction.id(), TransactionStatus.MANUAL_REVIEW,
                "LEDGER_REJECTED", TransactionUpdate.none());
        if (applied.accepted()) {
            publishManualReview(applied.transaction(), reason, correlationId);
        }
    }

    private void publishManualReview(PaymentTransaction transaction, String reason, String correlationId) {
        outbox.append(Topics.EVT_PAYMENT, transaction.id().toString(), EventEnvelope.of(
                EventTypes.PAYMENT_MANUAL_REVIEW_REQUIRED, "PaymentTransaction",
                transaction.id().toString(), correlationId, null, PRODUCER,
                new Payloads.PaymentManualReviewRequired(
                        transaction.id().toString(), transaction.externalRef(),
                        transaction.status().name(), reason)));

        audit.append("MANUAL_REVIEW_REQUIRED", "PaymentTransaction", transaction.id().toString(),
                correlationId, Map.of("reason", reason));
    }

    /**
     * Ecriture d'encaissement, telle que definie par le plan de comptes.
     *
     * <p>Le client envoie {@code amount}. Nos frais sont preleves au passage, et
     * l'operateur preleve les siens avant de nous crediter. D'ou quatre lignes :
     *
     * <pre>
     *   DR  float operateur        amount - commission operateur
     *   DR  charges commission     commission operateur
     *   CR  portefeuille client    amount - nos frais
     *   CR  produits commissions   nos frais
     * </pre>
     *
     * <p>Les deux cotes totalisent {@code amount}. Ignorer la commission de l'operateur
     * produirait une ecriture desequilibree — refusee par le grand livre, ce qui vaut
     * infiniment mieux que de ne pas s'en apercevoir.
     */
    private LedgerPort.EntryRequest collectionEntry(PaymentTransaction t) {
        return new LedgerPort.EntryRequest(
                // Cle derivee de l'identifiant de transaction, donc stable d'une
                // retentative a l'autre : c'est elle qui rend l'appel au grand livre sur
                // meme apres un arret entre l'appel et la validation locale.
                "collection:" + t.id(),
                null,
                t.externalRef(),
                "Encaissement %s %s".formatted(t.amount().toPlainString(), t.providerCode()),
                List.of(
                        LedgerPort.Line.debit(t.providerCode().floatAccount(), t.floatCredit()),
                        LedgerPort.Line.debit(LedgerAccounts.PROVIDER_COST, t.providerFee()),
                        LedgerPort.Line.credit(t.walletAccountRef(), t.walletCredit()),
                        LedgerPort.Line.credit(LedgerAccounts.FEE_INCOME, t.platformFee())));
    }
}
