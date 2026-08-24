package com.ocb.provider.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.platform.domain.money.Money;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.ReceivedEvent;
import com.ocb.platform.web.CorrelationIdFilter;
import com.ocb.provider.application.CollectionExecutionService;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.port.ProcessedMessageStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Consomme les commandes du moteur de paiement.
 *
 * <p>Prend la place exacte de l'echafaudage de la Phase 2, sur le meme topic et avec le
 * meme contrat. Aucun changement n'a ete necessaire cote {@code payment-service} : c'est
 * la demonstration que le decouplage par le bus tient ses promesses.
 *
 * <p>Deux protections contre le double prelevement, a deux niveaux. La deduplication
 * arrete un message rejoue par Kafka ; la contrainte d'unicite sur
 * {@code (provider_code, transaction_id)} arrete tout le reste, y compris une commande
 * reemise par le moteur de paiement lui-meme.
 */
@Component
public class ProviderCommandConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProviderCommandConsumer.class);

    private final CollectionExecutionService collections;
    private final ProcessedMessageStore processed;
    private final ObjectMapper mapper = EventJson.mapper();
    private final String consumerGroup;

    public ProviderCommandConsumer(CollectionExecutionService collections,
                                   ProcessedMessageStore processed,
                                   @Value("${ocb.kafka.groups.provider-commands}") String consumerGroup) {
        this.collections = collections;
        this.processed = processed;
        this.consumerGroup = consumerGroup;
    }

    @KafkaListener(topics = "#{T(com.ocb.platform.events.Topics).CMD_PROVIDER}",
            groupId = "${ocb.kafka.groups.provider-commands}",
            containerFactory = "providerKafkaListenerContainerFactory")
    @Transactional
    public void onCommand(String rawMessage) {
        ReceivedEvent event = read(rawMessage);
        if (event == null) {
            return;
        }

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            if (!processed.markProcessed(consumerGroup, event.eventId(), event.eventType())) {
                log.debug("Commande {} deja traitee, acquittee sans effet", event.eventId());
                return;
            }

            if (!EventTypes.PROVIDER_COLLECTION_EXECUTE.equals(event.eventType())) {
                // Type inconnu de ce service : on acquitte plutot que d'echouer, sans quoi
                // tout ajout retrocompatible sur le topic deviendrait une panne.
                log.debug("Type {} ignore par ce consommateur", event.eventType());
                return;
            }

            Payloads.ProviderCollectionExecute command =
                    event.payloadAs(mapper, Payloads.ProviderCollectionExecute.class);

            collections.execute(
                    UUID.fromString(command.transactionId()),
                    ProviderCode.valueOf(command.providerCode()),
                    command.externalRef(),
                    command.idempotencyKey(),
                    Money.parse(command.amount(), command.currency()),
                    command.payerMsisdn(),
                    event.correlationId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    /** Un message illisible ne le deviendra jamais : le retenter bloquerait la partition. */
    private ReceivedEvent read(String rawMessage) {
        try {
            return mapper.readValue(rawMessage, ReceivedEvent.class);
        } catch (Exception e) {
            log.error("Commande illisible ecartee : {}", e.getMessage());
            return null;
        }
    }
}
