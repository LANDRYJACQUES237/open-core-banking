package com.ocb.payment.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.payment.application.ProviderOutcomeService;
import com.ocb.payment.domain.port.ProcessedMessageStore;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.ReceivedEvent;
import com.ocb.platform.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consomme les issues d'operations remontees par l'adaptateur operateur.
 *
 * <p><b>Idempotent par construction.</b> Kafka garantit une livraison au moins une fois :
 * un redemarrage entre le traitement d'un message et la validation de son offset le fait
 * redelivrer. L'enregistrement dans {@code processed_message} a lieu <b>dans la meme
 * transaction que l'effet metier, et avant lui</b> — inverser l'ordre rouvrirait
 * exactement la fenetre que ce mecanisme ferme.
 *
 * <p><b>Deux niveaux de defense, pas un.</b> La deduplication arrete les doublons
 * techniques (meme message reemis). La machine a etats arrete les doublons <i>logiques</i>
 * : deux messages distincts, portant des identifiants differents, qui decrivent le meme
 * fait — typiquement un callback operateur et le resultat d'un polling qui arrivent
 * ensemble. Aucun des deux mecanismes ne remplace l'autre.
 *
 * <p><b>Un type inconnu n'est pas une erreur.</b> Le topic porte plusieurs types, et un
 * producteur peut en ajouter avant que ce service ne soit redeploye. On acquitte
 * silencieusement plutot que d'echouer : sans cela, tout ajout retrocompatible
 * deviendrait une panne.
 */
@Component
public class ProviderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProviderEventConsumer.class);
    public static final String GROUP = "payment-service.provider-events";

    private final ProviderOutcomeService outcomes;
    private final ProcessedMessageStore processed;
    private final ObjectMapper mapper = EventJson.mapper();

    public ProviderEventConsumer(ProviderOutcomeService outcomes, ProcessedMessageStore processed) {
        this.outcomes = outcomes;
        this.processed = processed;
    }

    @KafkaListener(topics = "#{T(com.ocb.platform.events.Topics).EVT_PROVIDER}",
            groupId = GROUP, containerFactory = "paymentKafkaListenerContainerFactory")
    @Transactional
    public void onProviderEvent(String rawMessage) {
        ReceivedEvent event = read(rawMessage);
        if (event == null) {
            return;
        }

        String correlationId = event.correlationId();
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            if (!processed.markProcessed(GROUP, event.eventId(), event.eventType())) {
                log.debug("Message {} deja traite, acquitte sans effet", event.eventId());
                return;
            }

            switch (event.eventType()) {
                case EventTypes.PROVIDER_OPERATION_ACCEPTED -> outcomes.onAccepted(
                        event.payloadAs(mapper, Payloads.ProviderOperationAccepted.class), correlationId);
                case EventTypes.PROVIDER_OPERATION_SUCCEEDED -> outcomes.onSucceeded(
                        event.payloadAs(mapper, Payloads.ProviderOperationSucceeded.class), correlationId);
                case EventTypes.PROVIDER_OPERATION_FAILED -> outcomes.onFailed(
                        event.payloadAs(mapper, Payloads.ProviderOperationFailed.class), correlationId);
                case EventTypes.PROVIDER_OPERATION_UNRESOLVED -> outcomes.onUnresolved(
                        event.payloadAs(mapper, Payloads.ProviderOperationUnresolved.class), correlationId);
                default -> log.debug("Type {} ignore par ce consommateur", event.eventType());
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /**
     * Un message illisible ne sera jamais lisible.
     *
     * <p>Le retenter bloquerait la partition indefiniment, et tous les messages suivants
     * de la meme cle attendraient derriere lui. On l'ecarte donc immediatement plutot que
     * de le faire passer par la file d'attente des retentatives.
     */
    private ReceivedEvent read(String rawMessage) {
        try {
            return mapper.readValue(rawMessage, ReceivedEvent.class);
        } catch (Exception e) {
            log.error("Message illisible ecarte : {}", e.getMessage());
            return null;
        }
    }
}
