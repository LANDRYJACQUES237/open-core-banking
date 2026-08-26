package com.ocb.provider.application;

import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.PollSchedule;
import com.ocb.provider.domain.ProviderOperation;
import com.ocb.provider.domain.port.AuditStore;
import com.ocb.provider.domain.port.OperationStore;
import com.ocb.provider.domain.port.ProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Relance de statut : le mecanisme qui tranche quand l'operateur se tait.
 *
 * <p>Le rappel donne la rapidite, la relance donne la certitude. Les deux sont
 * necessaires et aucun ne remplace l'autre : un rappel peut se perdre, arriver en double
 * ou des heures plus tard, tandis qu'une relance arrive toujours puisque c'est nous qui
 * la declenchons.
 *
 * <p><b>La course entre les deux est reelle et frequente.</b> Un rappel et une relance
 * peuvent viser la meme operation au meme instant. Elle est resolue par le verrou pose
 * sur la ligne, puis par la verification que l'operation attend encore une reponse : le
 * second arrive constate qu'il n'y a plus rien a faire et s'arrete.
 *
 * <p><b>Une relance sans reponse n'est pas un echec.</b> Elle consomme du budget, rien de
 * plus. Quand le budget s'epuise, l'operation devient {@code UNRESOLVED} — une ignorance
 * declaree, qui appelle un arbitrage humain plutot qu'une conclusion automatique.
 */
@Service
public class ReconciliationPoller {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationPoller.class);

    private final io.micrometer.core.instrument.Counter unresolved;

    private final OperationStore operations;
    private final ProviderClient providerClient;
    private final OperationEventPublisher events;
    private final AuditStore audit;
    private final PollSchedule schedule;
    private final int batchSize;

    /**
     * Transactions pilotees explicitement : le traitement d'une operation est appele
     * depuis une methode de la meme classe, ou une annotation serait contournee par le
     * proxy Spring et ne creerait aucune transaction.
     */
    private final TransactionTemplate transaction;

    public ReconciliationPoller(OperationStore operations,
                                ProviderClient providerClient,
                                OperationEventPublisher events,
                                AuditStore audit,
                                PlatformTransactionManager transactionManager,
                                io.micrometer.core.instrument.MeterRegistry meters,
                                @Value("${ocb.provider.poll.budget:PT24H}") Duration budget,
                                @Value("${ocb.provider.poll.batch-size:50}") int batchSize) {
        this.operations = operations;
        this.providerClient = providerClient;
        this.events = events;
        this.audit = audit;
        this.schedule = new PollSchedule(budget);
        this.batchSize = batchSize;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        // Compteur plutot que jauge : ce qui compte est le RYTHME auquel des operations
        // deviennent non resolues. Une jauge du nombre courant baisserait des qu'un humain
        // traite un dossier, et masquerait donc precisement l'aggravation qu'on veut voir.
        this.unresolved = meters.counter("ocb.provider.unresolved");
    }

    @Scheduled(fixedDelayString = "${ocb.provider.poll.interval:PT5S}")
    public void pollDueOperations() {
        try {
            int processed = pollBatch();
            if (processed > 0) {
                log.debug("Relance de statut : {} operation(s)", processed);
            }
        } catch (Exception e) {
            // L'ordonnanceur ne doit jamais mourir : une exception qui remonte arreterait
            // definitivement la tache, et les operations en attente ne seraient plus
            // jamais tranchees.
            log.error("Cycle de relance interrompu, reprise au prochain declenchement", e);
        }
    }

    public int pollBatch() {
        Integer count = transaction.execute(status -> {
            List<ProviderOperation> due = operations.lockDueForPolling(Instant.now(), batchSize);
            for (ProviderOperation operation : due) {
                pollOne(operation);
            }
            return due.size();
        });
        return count == null ? 0 : count;
    }

    private void pollOne(ProviderOperation operation) {
        // Un rappel a pu resoudre l'operation entre sa selection et le verrou. Le verifier
        // ici est ce qui empeche de publier deux issues pour un meme fait.
        if (!operation.awaitsPolling()) {
            return;
        }

        Instant now = Instant.now();
        int attempts = operation.pollAttempts() + 1;

        ProviderClient.ProviderStatus status;
        try {
            status = providerClient.pollStatus(
                    operation.providerCode(), operation.type(),
                    operation.externalRef(), operation.providerRef());
        } catch (ProviderClient.ProviderUnavailableException e) {
            // Toujours pas de reponse. On consomme du budget, on ne conclut rien.
            rescheduleOrExhaust(operation, attempts, now, e.getMessage());
            return;
        }

        switch (status.outcome()) {
            case SUCCEEDED -> {
                ProviderOperation resolved = operations.markResolved(operation.id(),
                        OperationStatus.SUCCEEDED,
                        status.providerRef() != null ? status.providerRef() : operation.providerRef(),
                        status.fee(), null, null);
                events.succeeded(resolved, "POLL", null);
                log.info("Operation {} tranchee par relance : succes", operation.transactionId());
            }
            case FAILED -> {
                ProviderOperation resolved = operations.markResolved(operation.id(),
                        OperationStatus.FAILED,
                        status.providerRef() != null ? status.providerRef() : operation.providerRef(),
                        null, status.errorCode(), status.errorMessage());
                events.failed(resolved, "POLL", null);
                log.info("Operation {} tranchee par relance : refus operateur",
                        operation.transactionId());
            }
            case PENDING -> rescheduleOrExhaust(operation, attempts, now, null);
        }
    }

    /**
     * Programme la relance suivante, ou declare l'ignorance si le budget est epuise.
     *
     * <p>C'est le seul endroit du systeme qui produit {@code UNRESOLVED}, et il ne le fait
     * jamais parce qu'une reponse serait negative — uniquement parce qu'il n'y a pas eu de
     * reponse du tout.
     */
    private void rescheduleOrExhaust(ProviderOperation operation, int attempts,
                                     Instant now, String lastError) {
        schedule.nextPollAt(operation.createdAt(), attempts, now).ifPresentOrElse(
                next -> operations.recordAttempt(operation.id(), attempts, now, next, lastError),
                () -> {
                    ProviderOperation exhausted =
                            operations.markBudgetExhausted(operation.id(), lastError);
                    events.unresolved(exhausted, null);
                    unresolved.increment();
                    audit.append("POLL_BUDGET_EXHAUSTED", "ProviderOperation",
                            operation.transactionId().toString(), null,
                            Map.of("attempts", attempts,
                                    "provider", operation.providerCode().name()));
                    log.error("Budget de relance epuise pour {} apres {} tentatives : "
                                    + "operation non resolue, arbitrage requis",
                            operation.transactionId(), attempts);
                });
    }
}
