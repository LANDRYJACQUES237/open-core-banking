package com.ocb.payment.application;

import com.ocb.payment.domain.DisbursementEntryRefs;
import com.ocb.payment.domain.LedgerAccounts;
import com.ocb.payment.domain.Msisdn;
import com.ocb.payment.domain.PaymentErrors;
import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.TransactionStatus;
import com.ocb.payment.domain.TransactionType;
import com.ocb.payment.domain.TransactionUpdate;
import com.ocb.payment.domain.port.AuditStore;
import com.ocb.payment.domain.port.IdempotencyStore;
import com.ocb.payment.domain.port.LedgerPort;
import com.ocb.payment.domain.port.TransactionStore;
import com.ocb.payment.domain.port.WalletLock;
import com.ocb.platform.domain.error.ConflictException;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.error.ResourceNotFoundException;
import com.ocb.platform.domain.money.Money;
import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.Topics;
import com.ocb.platform.outbox.OutboxWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Premiere etape de la saga de decaissement : engager les fonds.
 *
 * <p><b>L'inversion par rapport a l'encaissement est tout le sujet.</b> Un encaissement
 * n'ecrit au grand livre qu'apres confirmation de l'operateur : tant que rien n'est
 * confirme, rien n'a bouge, et un refus se solde par un simple echec. Un decaissement ne
 * peut pas fonctionner ainsi — on n'envoie pas de l'argent qu'on n'a pas preleve. Les
 * fonds quittent donc le portefeuille <b>avant</b> l'appel a l'operateur.
 *
 * <p>C'est cette inversion qui cree quelque chose a defaire, et donc qui rend une saga
 * necessaire ici alors qu'elle serait de la mise en scene ailleurs.
 *
 * <p>Les fonds ne partent pas dans le vide : ils stationnent sur le compte de passage
 * {@value com.ocb.payment.domain.LedgerAccounts#DISBURSEMENT_SUSPENSE}, d'ou ils repartent
 * vers le float de l'operateur a la livraison, ou reviennent au client par
 * contre-passation. Tant qu'un montant y stationne, la saga n'est pas terminee.
 */
@Service
public class DisbursementService {

    private static final String PRODUCER = "payment-service";

    private final TransactionStore transactions;
    private final IdempotencyStore idempotency;
    private final TransactionStateService stateService;
    private final WalletLock walletLock;
    private final LedgerPort ledger;
    private final OutboxWriter outbox;
    private final AuditStore audit;
    private final DisbursementFeePolicy feePolicy;

    public DisbursementService(TransactionStore transactions,
                               IdempotencyStore idempotency,
                               TransactionStateService stateService,
                               WalletLock walletLock,
                               LedgerPort ledger,
                               OutboxWriter outbox,
                               AuditStore audit,
                               DisbursementFeePolicy feePolicy) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.stateService = stateService;
        this.walletLock = walletLock;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.feePolicy = feePolicy;
    }

    @Transactional
    public Result request(DisbursementCommand command) {
        Money amount = Money.parse(command.amount(), command.currency());
        Msisdn payee = Msisdn.of(command.payeeMsisdn());

        String fingerprint = RequestFingerprint.ofDisbursement(
                command.externalRef(), amount, command.walletAccountRef(),
                command.providerCode().name(), payee);

        IdempotencyStore.Claim claim =
                idempotency.claim(command.clientId(), command.idempotencyKey(), fingerprint);

        switch (claim.outcome()) {
            case REPLAY -> {
                PaymentTransaction existing = transactions.find(claim.resourceId()).orElseThrow(() ->
                        new ResourceNotFoundException(PaymentErrors.TRANSACTION_NOT_FOUND,
                                "Transaction %s introuvable".formatted(claim.resourceId())));
                return new Result(existing, false);
            }
            case MISMATCH -> throw new InvariantViolationException(
                    PaymentErrors.IDEMPOTENCY_KEY_REUSED,
                    ("La cle d'idempotence %s a deja servi pour une demande differente. "
                            + "Ce n'est pas un rejeu : utilisez une cle distincte pour une "
                            + "operation distincte").formatted(command.idempotencyKey()));
            case IN_PROGRESS -> throw new ConflictException(
                    PaymentErrors.IDEMPOTENT_REQUEST_IN_PROGRESS,
                    "Une requete portant cette cle est en cours de traitement, reessayez");
            case FRESH -> {
                // On continue.
            }
        }

        Money platformFee = feePolicy.forDisbursement(amount);
        Money totalDebit = amount.add(platformFee);

        // Le verrou est pris AVANT la lecture du solde, et tenu jusqu'a la validation.
        // C'est ce qui rend la lecture et l'ecriture indivisibles vis-a-vis d'un second
        // decaissement sur le meme portefeuille. Le relacher entre les deux ramenerait
        // exactement la course qu'il est cense fermer.
        walletLock.lockForUpdate(command.walletAccountRef());

        Money available = ledger.balanceOf(command.walletAccountRef());
        if (available.compareTo(totalDebit) < 0) {
            // Refus franc : rien n'a ete ecrit, rien n'a ete engage, aucun ordre n'est
            // parti. La cle d'idempotence est liberee par l'annulation de la transaction,
            // ce qui laisse l'appelant reessayer avec la meme cle apres alimentation du
            // portefeuille.
            throw new InvariantViolationException(
                    PaymentErrors.INSUFFICIENT_FUNDS,
                    ("Le portefeuille %s ne couvre pas %s (frais compris) ; solde disponible %s")
                            .formatted(command.walletAccountRef(),
                                    totalDebit.toPlainString(), available.toPlainString()));
        }

        // Derive de l'appelant et de sa cle, jamais tire au hasard : l'ecriture comptable
        // qui suit est validee chez le grand livre avant que cette transaction-ci ne le
        // soit. Voir RequestIdentity pour la fenetre que cela ferme.
        UUID transactionId = RequestIdentity.of(command.clientId(), command.idempotencyKey());
        PaymentTransaction created = transactions.create(new PaymentTransaction(
                transactionId, command.externalRef(), TransactionType.DISBURSEMENT,
                TransactionStatus.CREATED, amount, platformFee, null,
                command.walletAccountRef(), command.providerCode(), payee.masked(),
                null, null, null, null, null, null, 0));

        transactions.recordTransition(transactionId, null, TransactionStatus.CREATED,
                "DISBURSEMENT_REQUESTED", true, null, command.correlationId());

        // L'engagement des fonds. Appel synchrone, sous verrou : c'est la limite assumee
        // de cette conception, documentee dans le README. En contrepartie, aucune fenetre
        // ne separe le controle de solde de l'ecriture qui le consomme.
        String reservationRef = ledger.post(reservationEntry(
                transactionId, command, amount, platformFee, totalDebit));

        outbox.append(Topics.CMD_PROVIDER, transactionId.toString(), EventEnvelope.of(
                EventTypes.PROVIDER_DISBURSEMENT_EXECUTE, "PaymentTransaction",
                transactionId.toString(), command.correlationId(), null, PRODUCER,
                new Payloads.ProviderDisbursementExecute(
                        transactionId.toString(), command.providerCode().name(),
                        amount.toPlainString(), amount.currencyCode(), payee.full(),
                        command.externalRef(), "disbursement:" + transactionId)));

        TransactionStateService.Applied applied = stateService.apply(
                transactionId, TransactionStatus.PENDING_PROVIDER,
                "DISBURSEMENT_COMMAND_EMITTED", TransactionUpdate.posted(reservationRef));

        outbox.append(Topics.EVT_PAYMENT, transactionId.toString(), EventEnvelope.of(
                EventTypes.PAYMENT_DISBURSEMENT_REQUESTED, "PaymentTransaction",
                transactionId.toString(), command.correlationId(), null, PRODUCER,
                new Payloads.PaymentDisbursementRequested(
                        transactionId.toString(), command.externalRef(), amount.toPlainString(),
                        amount.currencyCode(), platformFee.toPlainString(),
                        command.walletAccountRef(), command.providerCode().name(),
                        reservationRef, payee.masked())));

        audit.append("DISBURSEMENT_REQUESTED", "PaymentTransaction", transactionId.toString(),
                command.correlationId(),
                Map.of("amount", amount.toPlainString(),
                        "currency", amount.currencyCode(),
                        "platformFee", platformFee.toPlainString(),
                        "provider", command.providerCode().name(),
                        "reservationEntryRef", reservationRef,
                        "maskedMsisdn", payee.masked()));

        idempotency.complete(command.clientId(), command.idempotencyKey(), 202, null, transactionId);

        return new Result(applied.accepted() ? applied.transaction() : created, true);
    }

    /**
     * Etape 1 du decaissement : engagement des fonds.
     *
     * <pre>
     *   DR  portefeuille client    montant + nos frais
     *   CR  compte de passage      montant
     *   CR  produits commissions   nos frais
     * </pre>
     *
     * <p>Nos frais sont acquis des l'engagement, et non a la livraison : c'est la prise en
     * charge de l'ordre qui est facturee. En consequence, une compensation les rend elle
     * aussi — la contre-passation inverse les trois lignes, pas seulement la premiere.
     */
    private LedgerPort.EntryRequest reservationEntry(UUID transactionId,
                                                     DisbursementCommand command,
                                                     Money amount,
                                                     Money platformFee,
                                                     Money totalDebit) {
        return new LedgerPort.EntryRequest(
                // Cle derivee de l'identifiant de transaction : stable d'une retentative a
                // l'autre, ce qui rend l'appel sur meme si ce service tombe entre l'appel
                // et la validation locale.
                "disbursement-reservation:" + transactionId,
                DisbursementEntryRefs.reservation(transactionId),
                command.externalRef(),
                "Engagement decaissement %s %s".formatted(
                        amount.toPlainString(), command.providerCode()),
                List.of(
                        LedgerPort.Line.debit(command.walletAccountRef(), totalDebit),
                        LedgerPort.Line.credit(LedgerAccounts.DISBURSEMENT_SUSPENSE, amount),
                        LedgerPort.Line.credit(LedgerAccounts.FEE_INCOME, platformFee)));
    }

    public record Result(PaymentTransaction transaction, boolean created) {
    }
}
