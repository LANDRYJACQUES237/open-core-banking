package com.ocb.payment.application;

import com.ocb.payment.domain.LedgerAccounts;
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
 * Transfert entre deux portefeuilles.
 *
 * <p><b>Il n'y a pas de saga ici, et c'est le propos.</b> Un transfert ne traverse aucune
 * frontiere de service : il ne fait bouger que des comptes du grand livre, dans une seule
 * ecriture equilibree, a l'interieur d'une transaction ACID. Il n'existe aucun etat
 * intermediaire ou l'argent aurait quitte un portefeuille sans etre arrive dans l'autre,
 * donc rien a compenser.
 *
 * <p>Lui ajouter des etapes intermediaires, un compte de passage et une compensation
 * reviendrait a payer la complexite d'un protocole distribue pour un probleme qui n'en est
 * pas un. Une saga se justifie quand l'atomicite est <b>impossible</b>, pas quand elle est
 * simplement disponible.
 *
 * <p>C'est la contrepartie utile du decaissement : elle montre que la saga y est presente
 * parce qu'elle y est necessaire, et non par gout du motif.
 *
 * <p>Ce qui reste commun aux deux : l'interdiction du decouvert. Debiter un portefeuille
 * demande de lire son solde puis d'ecrire en fonction de ce qu'on a lu, et cette sequence
 * doit etre serialisee ici aussi — sans quoi un transfert et un decaissement simultanes
 * sur le meme portefeuille se croiraient tous deux finançables.
 */
@Service
public class TransferService {

    private static final String PRODUCER = "payment-service";

    private final TransactionStore transactions;
    private final IdempotencyStore idempotency;
    private final TransactionStateService stateService;
    private final WalletLock walletLock;
    private final LedgerPort ledger;
    private final OutboxWriter outbox;
    private final AuditStore audit;
    private final TransferFeePolicy feePolicy;

    public TransferService(TransactionStore transactions,
                           IdempotencyStore idempotency,
                           TransactionStateService stateService,
                           WalletLock walletLock,
                           LedgerPort ledger,
                           OutboxWriter outbox,
                           AuditStore audit,
                           TransferFeePolicy feePolicy) {
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
    public Result request(TransferCommand command) {
        Money amount = Money.parse(command.amount(), command.currency());

        if (command.fromWalletAccountRef().equals(command.toWalletAccountRef())) {
            // Un transfert vers soi-meme est comptablement nul, mais preleverait des
            // frais. Le refuser vaut mieux que d'encaisser une commission sur une
            // operation qui ne deplace rien.
            throw new InvariantViolationException(
                    PaymentErrors.SAME_WALLET_TRANSFER,
                    "Les portefeuilles source et destination sont identiques");
        }

        String fingerprint = RequestFingerprint.ofTransfer(
                command.externalRef(), amount,
                command.fromWalletAccountRef(), command.toWalletAccountRef());

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

        Money platformFee = feePolicy.forTransfer(amount);
        Money totalDebit = amount.add(platformFee);

        // Seul le portefeuille debite est verrouille. Crediter ne peut pas mettre a
        // decouvert, donc n'a rien a serialiser — et ne verrouiller qu'un cote supprime
        // par construction le risque d'interblocage entre un transfert A vers B et un
        // transfert B vers A, qui prendraient leurs verrous dans l'ordre inverse.
        walletLock.lockForUpdate(command.fromWalletAccountRef());

        Money available = ledger.balanceOf(command.fromWalletAccountRef());
        if (available.compareTo(totalDebit) < 0) {
            throw new InvariantViolationException(
                    PaymentErrors.INSUFFICIENT_FUNDS,
                    ("Le portefeuille %s ne couvre pas %s (frais compris) ; solde disponible %s")
                            .formatted(command.fromWalletAccountRef(),
                                    totalDebit.toPlainString(), available.toPlainString()));
        }

        // Derive, comme pour le decaissement : l'ecriture comptable est validee chez le
        // grand livre avant que cette transaction-ci ne le soit.
        UUID transactionId = RequestIdentity.of(command.clientId(), command.idempotencyKey());

        PaymentTransaction created = transactions.create(new PaymentTransaction(
                transactionId, command.externalRef(), TransactionType.TRANSFER,
                TransactionStatus.CREATED, amount, platformFee, null,
                command.fromWalletAccountRef(), null, null,
                null, null, null, null, null, null, 0));

        transactions.recordTransition(transactionId, null, TransactionStatus.CREATED,
                "TRANSFER_REQUESTED", true, null, command.correlationId());

        // Aucun passage par PENDING_PROVIDER : il n'y a pas d'operateur a attendre. La
        // machine a etats l'autorise explicitement pour ce seul cas.
        TransactionStateService.Applied posting = stateService.apply(
                transactionId, TransactionStatus.POSTING,
                "TRANSFER_POSTING_STARTED", TransactionUpdate.none());
        if (!posting.accepted()) {
            return new Result(created, true);
        }

        String entryRef = ledger.post(transferEntry(
                transactionId, command, amount, platformFee, totalDebit));

        TransactionStateService.Applied completed = stateService.apply(
                transactionId, TransactionStatus.COMPLETED,
                "TRANSFER_POSTED", TransactionUpdate.posted(entryRef));

        PaymentTransaction settled = completed.accepted() ? completed.transaction() : created;

        outbox.append(Topics.EVT_PAYMENT, transactionId.toString(), EventEnvelope.of(
                EventTypes.PAYMENT_TRANSFER_COMPLETED, "PaymentTransaction",
                transactionId.toString(), command.correlationId(), null, PRODUCER,
                new Payloads.PaymentTransferCompleted(
                        transactionId.toString(), command.externalRef(),
                        amount.toPlainString(), amount.currencyCode(),
                        platformFee.toPlainString(), command.fromWalletAccountRef(),
                        command.toWalletAccountRef(), entryRef)));

        audit.append("TRANSFER_COMPLETED", "PaymentTransaction", transactionId.toString(),
                command.correlationId(),
                Map.of("amount", amount.toPlainString(),
                        "currency", amount.currencyCode(),
                        "platformFee", platformFee.toPlainString(),
                        "from", command.fromWalletAccountRef(),
                        "to", command.toWalletAccountRef(),
                        "ledgerEntryRef", entryRef));

        idempotency.complete(command.clientId(), command.idempotencyKey(), 201, null, transactionId);

        return new Result(settled, true);
    }

    /**
     * L'ecriture d'un transfert : une seule, equilibree.
     *
     * <pre>
     *   DR  portefeuille source     montant + nos frais
     *   CR  portefeuille destinataire  montant
     *   CR  produits commissions    nos frais
     * </pre>
     *
     * <p>Le destinataire recoit le montant demande ; c'est l'emetteur qui supporte les
     * frais. L'inverse — retenir les frais sur le montant recu — obligerait l'emetteur a
     * calculer a l'envers pour qu'un montant rond arrive a destination.
     *
     * <p>La ligne de commission disparait quand la commission est nulle. Le grand livre
     * refuse toute ligne de montant nul — une ligne qui ne deplace rien masque
     * generalement un bug de calcul — si bien qu'un bareme configure a zero, cas
     * parfaitement legitime, rendrait sinon tout transfert impossible avec une erreur
     * comptable difficile a relier a sa cause.
     */
    private LedgerPort.EntryRequest transferEntry(UUID transactionId,
                                                  TransferCommand command,
                                                  Money amount,
                                                  Money platformFee,
                                                  Money totalDebit) {
        return new LedgerPort.EntryRequest(
                "transfer:" + transactionId,
                "TRANSFER-" + transactionId,
                command.externalRef(),
                "Transfert %s de %s vers %s".formatted(
                        amount.toPlainString(),
                        command.fromWalletAccountRef(), command.toWalletAccountRef()),
                feeAware(
                        LedgerPort.Line.debit(command.fromWalletAccountRef(), totalDebit),
                        LedgerPort.Line.credit(command.toWalletAccountRef(), amount),
                        platformFee));
    }

    /** Ajoute la ligne de commission seulement si elle deplace quelque chose. */
    static List<LedgerPort.Line> feeAware(LedgerPort.Line debit,
                                          LedgerPort.Line credit,
                                          Money platformFee) {
        if (platformFee.amount().signum() == 0) {
            return List.of(debit, credit);
        }
        return List.of(debit, credit, LedgerPort.Line.credit(LedgerAccounts.FEE_INCOME, platformFee));
    }

    public record Result(PaymentTransaction transaction, boolean created) {
    }
}
