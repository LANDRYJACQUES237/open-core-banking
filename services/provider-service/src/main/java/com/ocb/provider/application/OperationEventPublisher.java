package com.ocb.provider.application;

import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.Topics;
import com.ocb.platform.outbox.OutboxWriter;
import com.ocb.provider.domain.ProviderOperation;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Publie les issues d'operations, toujours via l'outbox.
 *
 * <p>Regroupe au meme endroit les quatre evenements que ce service emet, pour que la
 * regle qui les gouverne se lise d'un coup : <b>seule une reponse de l'operateur produit
 * un succes ou un echec</b>. L'absence de reponse ne produit qu'un
 * {@code provider.operation.unresolved}, qui dit explicitement qu'on ne sait pas.
 */
@Component
public class OperationEventPublisher {

    private static final String PRODUCER = "provider-service";

    private final OutboxWriter outbox;

    public OperationEventPublisher(OutboxWriter outbox) {
        this.outbox = outbox;
    }

    public void accepted(ProviderOperation operation, String correlationId) {
        publish(operation, correlationId, EventTypes.PROVIDER_OPERATION_ACCEPTED,
                new Payloads.ProviderOperationAccepted(
                        operation.transactionId().toString(),
                        operation.providerCode().name(),
                        operation.providerRef(),
                        Instant.now()));
    }

    public void succeeded(ProviderOperation operation, String resolvedBy, String correlationId) {
        publish(operation, correlationId, EventTypes.PROVIDER_OPERATION_SUCCEEDED,
                new Payloads.ProviderOperationSucceeded(
                        operation.transactionId().toString(),
                        operation.providerCode().name(),
                        operation.providerRef(),
                        operation.providerFee() == null ? "0" : operation.providerFee().toPlainString(),
                        operation.amount().currencyCode(),
                        Instant.now(),
                        resolvedBy));
    }

    public void failed(ProviderOperation operation, String resolvedBy, String correlationId) {
        publish(operation, correlationId, EventTypes.PROVIDER_OPERATION_FAILED,
                new Payloads.ProviderOperationFailed(
                        operation.transactionId().toString(),
                        operation.providerCode().name(),
                        operation.providerRef(),
                        operation.errorCode(),
                        operation.errorMessage(),
                        resolvedBy));
    }

    /**
     * Budget de relance epuise.
     *
     * <p>Ce n'est pas un echec, et l'evenement porte un type distinct precisement pour
     * qu'aucun consommateur ne puisse le confondre avec un. Le moteur de paiement le
     * traduira en revue manuelle, pas en transaction perdue.
     */
    public void unresolved(ProviderOperation operation, String correlationId) {
        publish(operation, correlationId, EventTypes.PROVIDER_OPERATION_UNRESOLVED,
                new Payloads.ProviderOperationUnresolved(
                        operation.transactionId().toString(),
                        operation.providerCode().name(),
                        operation.providerRef(),
                        operation.pollAttempts(),
                        operation.status().name()));
    }

    private void publish(ProviderOperation operation, String correlationId,
                         String eventType, Object payload) {
        // Cle de partition : l'identifiant de transaction. C'est ce qui garantit que
        // l'accuse de reception et l'issue arrivent dans l'ordre chez le moteur de
        // paiement, dont la machine a etats refuserait une confirmation recue avant
        // l'acceptation.
        outbox.append(Topics.EVT_PROVIDER, operation.transactionId().toString(),
                EventEnvelope.of(eventType, "ProviderOperation",
                        operation.transactionId().toString(), correlationId, null, PRODUCER, payload));
    }
}
