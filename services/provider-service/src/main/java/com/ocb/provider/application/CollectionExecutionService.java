package com.ocb.provider.application;

import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.OperationType;
import com.ocb.provider.domain.PollSchedule;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.ProviderOperation;
import com.ocb.provider.domain.port.AuditStore;
import com.ocb.provider.domain.port.OperationStore;
import com.ocb.provider.domain.port.ProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Emission d'une demande d'encaissement vers un operateur.
 *
 * <p>Trois issues, et la troisieme est celle qui compte.
 *
 * <ol>
 *   <li>l'operateur <b>accepte</b> : on enregistre sa reference, on programme une relance
 *       de securite et on publie l'accuse de reception ;
 *   <li>l'operateur <b>refuse</b> : c'est une reponse definitive, on publie l'echec ;
 *   <li>l'operateur <b>ne repond pas</b> : on ne publie <b>rien</b>. L'operation reste en
 *       attente et une relance est programmee. Publier un echec ici serait affirmer que
 *       rien n'a bouge alors qu'on n'en sait rien — la demande est peut-etre parvenue et
 *       le client a peut-etre deja ete debite.
 * </ol>
 */
@Service
public class CollectionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionExecutionService.class);

    private final OperationStore operations;
    private final ProviderClient providerClient;
    private final OperationEventPublisher events;
    private final AuditStore audit;
    private final PollSchedule schedule;
    private final String callbackBaseUrl;

    public CollectionExecutionService(OperationStore operations,
                                      ProviderClient providerClient,
                                      OperationEventPublisher events,
                                      AuditStore audit,
                                      @Value("${ocb.provider.poll.budget:PT24H}") java.time.Duration budget,
                                      @Value("${ocb.provider.callback-base-url}") String callbackBaseUrl) {
        this.operations = operations;
        this.providerClient = providerClient;
        this.events = events;
        this.audit = audit;
        this.schedule = new PollSchedule(budget);
        this.callbackBaseUrl = callbackBaseUrl;
    }

    @Transactional
    public void execute(UUID transactionId,
                        ProviderCode providerCode,
                        String externalRef,
                        String idempotencyKey,
                        Money amount,
                        String payerMsisdn,
                        String correlationId) {

        Instant now = Instant.now();

        OperationStore.Created created = operations.createOrGet(new ProviderOperation(
                UUID.randomUUID(), transactionId, providerCode, OperationType.COLLECTION,
                externalRef, idempotencyKey, payerMsisdn, amount,
                null, OperationStatus.PENDING, null, null, null, null,
                0, 0, null, schedule.nextPollAt(now, 0, now).orElse(null), false,
                now, now, 0));

        // Commande rejouee. L'unicite en base a fait son travail : on ne rappelle surtout
        // pas l'operateur, ce qui creerait un second prelevement.
        if (!created.created()) {
            log.info("Commande deja traitee pour la transaction {}, aucun nouvel appel operateur",
                    transactionId);
            return;
        }

        ProviderOperation operation = created.operation();

        ProviderClient.ProviderStatus status;
        try {
            status = providerClient.initiateCollection(new ProviderClient.CollectionRequest(
                    providerCode, externalRef, idempotencyKey, amount, payerMsisdn,
                    callbackBaseUrl + "/webhooks/" + providerCode.name()));
        } catch (ProviderClient.ProviderUnavailableException e) {
            // Le point le plus important du service. On ne conclut pas, on programme une
            // relance et on se tait. Le moteur de paiement laissera sa transaction en
            // attente, ce qui est exactement l'etat de la connaissance reelle.
            Instant nextPoll = schedule.nextPollAt(now, 0, now).orElse(null);
            operations.recordAttempt(operation.id(), 0, now, nextPoll, e.getMessage());

            log.warn("Operateur {} sans reponse pour {} : aucune conclusion, relance programmee a {}",
                    providerCode, transactionId, nextPoll);
            audit.append("PROVIDER_NO_RESPONSE", "ProviderOperation", transactionId.toString(),
                    correlationId, Map.of("provider", providerCode.name(), "phase", "initiation"));
            return;
        }

        switch (status.outcome()) {
            case PENDING -> {
                ProviderOperation accepted = operations.markAccepted(
                        operation.id(), status.providerRef(),
                        schedule.nextPollAt(now, 0, now).orElse(null));
                events.accepted(accepted, correlationId);
            }
            case SUCCEEDED -> {
                ProviderOperation resolved = operations.markResolved(
                        operation.id(), OperationStatus.SUCCEEDED, status.providerRef(),
                        status.fee(), null, null);
                // Certains operateurs concluent en ligne : on publie l'accuse puis
                // l'issue, dans cet ordre, pour que la machine a etats du moteur de
                // paiement voie une progression coherente.
                events.accepted(resolved, correlationId);
                events.succeeded(resolved, "SYNC", correlationId);
            }
            case FAILED -> {
                ProviderOperation resolved = operations.markResolved(
                        operation.id(), OperationStatus.FAILED, status.providerRef(),
                        null, status.errorCode(), status.errorMessage());
                events.accepted(resolved, correlationId);
                events.failed(resolved, "SYNC", correlationId);
            }
        }
    }
}
