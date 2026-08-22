package com.ocb.payment.application;

import com.ocb.payment.domain.Msisdn;
import com.ocb.payment.domain.PaymentErrors;
import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.TransactionStatus;
import com.ocb.payment.domain.TransactionType;
import com.ocb.payment.domain.TransactionUpdate;
import com.ocb.payment.domain.port.AuditStore;
import com.ocb.payment.domain.port.IdempotencyStore;
import com.ocb.payment.domain.port.TransactionStore;
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

import java.util.Map;
import java.util.UUID;

/**
 * Prise en charge d'une demande d'encaissement.
 *
 * <p><b>Tout se passe dans une seule transaction</b>, y compris la reservation de la cle
 * d'idempotence. Ce n'est pas un detail : c'est ce qui rend deux requetes simultanees
 * portant la meme cle mutuellement exclusives. La seconde attend sur l'insertion de la
 * cle, puis constate que la premiere a termine et rend son resultat, au lieu de declencher
 * un second prelevement.
 *
 * <p><b>Aucun appel reseau n'a lieu ici.</b> La commande destinee a l'operateur est
 * deposee dans l'outbox, dans cette meme transaction, et publiee ensuite par le relais.
 * Appeler l'operateur directement reintroduirait le dual-write : la base validerait, la
 * publication echouerait, et la demande resterait en attente d'une commande qui ne
 * partirait jamais.
 */
@Service
public class CollectionService {

    private static final String PRODUCER = "payment-service";

    private final TransactionStore transactions;
    private final IdempotencyStore idempotency;
    private final TransactionStateService stateService;
    private final OutboxWriter outbox;
    private final AuditStore audit;
    private final FeePolicy feePolicy;

    public CollectionService(TransactionStore transactions,
                             IdempotencyStore idempotency,
                             TransactionStateService stateService,
                             OutboxWriter outbox,
                             AuditStore audit,
                             FeePolicy feePolicy) {
        this.transactions = transactions;
        this.idempotency = idempotency;
        this.stateService = stateService;
        this.outbox = outbox;
        this.audit = audit;
        this.feePolicy = feePolicy;
    }

    @Transactional
    public Result request(CollectionCommand command) {
        Money amount = Money.parse(command.amount(), command.currency());
        Msisdn payer = Msisdn.of(command.payerMsisdn());

        String fingerprint = RequestFingerprint.ofCollection(
                command.externalRef(), amount, command.walletAccountRef(),
                command.providerCode().name(), payer);

        IdempotencyStore.Claim claim =
                idempotency.claim(command.clientId(), command.idempotencyKey(), fingerprint);

        switch (claim.outcome()) {
            case REPLAY -> {
                // Rejeu : on rend l'etat courant de la transaction plutot que la reponse
                // figee du premier appel. Sur une operation asynchrone, l'appelant qui
                // rejoue veut savoir ou elle en est, pas relire un accuse de reception.
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

        Money platformFee = feePolicy.forCollection(amount);
        if (platformFee.compareTo(amount) >= 0) {
            throw new InvariantViolationException(
                    PaymentErrors.INVALID_AMOUNT,
                    "Les frais (%s) absorberaient la totalite du montant (%s)".formatted(platformFee, amount));
        }

        UUID transactionId = UUID.randomUUID();
        PaymentTransaction created = transactions.create(new PaymentTransaction(
                transactionId, command.externalRef(), TransactionType.COLLECTION,
                TransactionStatus.CREATED, amount, platformFee, null,
                command.walletAccountRef(), command.providerCode(), payer.masked(),
                null, null, null, null, null, null, 0));

        // Etat initial journalise comme une transition depuis rien : l'historique raconte
        // alors la vie complete de la transaction, sans trou au demarrage.
        transactions.recordTransition(transactionId, null, TransactionStatus.CREATED,
                "COLLECTION_REQUESTED", true, null, command.correlationId());

        // La commande operateur part par l'outbox. Le numero complet n'apparait que la,
        // parce que l'adaptateur operateur en a besoin ; la transaction persistee, elle,
        // ne garde que la forme masquee.
        outbox.append(Topics.CMD_PROVIDER, transactionId.toString(), EventEnvelope.of(
                EventTypes.PROVIDER_COLLECTION_EXECUTE, "PaymentTransaction",
                transactionId.toString(), command.correlationId(), null, PRODUCER,
                new Payloads.ProviderCollectionExecute(
                        transactionId.toString(), command.providerCode().name(),
                        amount.toPlainString(), amount.currencyCode(), payer.full(),
                        command.externalRef(), "collection:" + transactionId)));

        TransactionStateService.Applied applied = stateService.apply(
                transactionId, TransactionStatus.PENDING_PROVIDER,
                "COLLECTION_COMMAND_EMITTED", TransactionUpdate.none());

        outbox.append(Topics.EVT_PAYMENT, transactionId.toString(), EventEnvelope.of(
                EventTypes.PAYMENT_COLLECTION_REQUESTED, "PaymentTransaction",
                transactionId.toString(), command.correlationId(), null, PRODUCER,
                new Payloads.PaymentCollectionRequested(
                        transactionId.toString(), command.externalRef(), amount.toPlainString(),
                        amount.currencyCode(), platformFee.toPlainString(),
                        command.walletAccountRef(), command.providerCode().name(), payer.masked())));

        audit.append("COLLECTION_REQUESTED", "PaymentTransaction", transactionId.toString(),
                command.correlationId(),
                Map.of("amount", amount.toPlainString(),
                        "currency", amount.currencyCode(),
                        "provider", command.providerCode().name(),
                        "maskedMsisdn", payer.masked()));

        idempotency.complete(command.clientId(), command.idempotencyKey(), 202, null, transactionId);

        return new Result(applied.accepted() ? applied.transaction() : created, true);
    }

    public record Result(PaymentTransaction transaction, boolean created) {
    }
}
