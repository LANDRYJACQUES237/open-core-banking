package com.ocb.notification.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.notification.application.PaymentEventNotifier;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.ReceivedEvent;
import com.ocb.platform.kafka.ProcessedMessageStore;
import com.ocb.platform.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Second lecteur de {@code ocb.evt.payment.v1}.
 *
 * <p>payment-service publie ces evenements pour qui veut les entendre ; personne n'attend
 * de reponse. C'est la difference entre un evenement et une commande, et c'est ce qui
 * permet d'ajouter ce consommateur sans toucher au producteur.
 *
 * <p>La deduplication est portee par {@code processed_message}, dont la cle est composite
 * — groupe de consommation et identifiant d'evenement. Sans cette composition, ce service
 * masquerait les evenements a payment-service ou l'inverse, selon qui consomme le premier.
 *
 * <p>Un type inconnu est acquitte sans effet plutot que traite en erreur : sinon, tout
 * ajout retrocompatible sur le topic deviendrait une panne de ce service.
 */
@Component
public class PaymentEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    private final PaymentEventNotifier notifier;
    private final ProcessedMessageStore processed;
    private final ObjectMapper mapper = EventJson.mapper();
    private final String consumerGroup;

    public PaymentEventConsumer(PaymentEventNotifier notifier,
                                ProcessedMessageStore processed,
                                @Value("${ocb.kafka.groups.payment-events}") String consumerGroup) {
        this.notifier = notifier;
        this.processed = processed;
        this.consumerGroup = consumerGroup;
    }

    @KafkaListener(topics = "#{T(com.ocb.platform.events.Topics).EVT_PAYMENT}",
            groupId = "${ocb.kafka.groups.payment-events}",
            containerFactory = "ocbKafkaListenerContainerFactory")
    @Transactional
    public void onPaymentEvent(String rawMessage) {
        ReceivedEvent event = read(rawMessage);
        if (event == null) {
            return;
        }

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            if (!processed.markProcessed(consumerGroup, event.eventId(), event.eventType())) {
                log.debug("Evenement {} deja notifie, acquitte sans effet", event.eventId());
                return;
            }
            dispatch(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void dispatch(ReceivedEvent event) {
        switch (event.eventType()) {
            case EventTypes.PAYMENT_COLLECTION_COMPLETED -> notifier.onCollectionCompleted(
                    event.payloadAs(mapper, Payloads.PaymentCollectionCompleted.class),
                    event.correlationId());

            case EventTypes.PAYMENT_COLLECTION_FAILED -> notifier.onCollectionFailed(
                    event.payloadAs(mapper, Payloads.PaymentCollectionFailed.class),
                    event.correlationId());

            case EventTypes.PAYMENT_DISBURSEMENT_COMPLETED -> notifier.onDisbursementCompleted(
                    event.payloadAs(mapper, Payloads.PaymentDisbursementCompleted.class),
                    event.correlationId());

            case EventTypes.PAYMENT_DISBURSEMENT_REVERSED -> notifier.onDisbursementReversed(
                    event.payloadAs(mapper, Payloads.PaymentDisbursementReversed.class),
                    event.correlationId());

            case EventTypes.PAYMENT_TRANSFER_COMPLETED -> notifier.onTransferCompleted(
                    event.payloadAs(mapper, Payloads.PaymentTransferCompleted.class),
                    event.correlationId());

            case EventTypes.PAYMENT_MANUAL_REVIEW_REQUIRED -> notifier.onManualReviewRequired(
                    event.payloadAs(mapper, Payloads.PaymentManualReviewRequired.class),
                    event.correlationId());

            default ->
                // payment.disbursement.requested en fait partie : annoncer au client que
                // son ordre est parti n'apporte rien qu'il ne sache deja, puisqu'il vient
                // de le demander. Tout evenement n'appelle pas un message.
                log.debug("Type {} sans notification associee", event.eventType());
        }
    }

    /** Un message illisible ne le deviendra jamais : le retenter bloquerait la partition. */
    private ReceivedEvent read(String rawMessage) {
        try {
            return mapper.readValue(rawMessage, ReceivedEvent.class);
        } catch (Exception e) {
            log.error("Evenement illisible ecarte : {}", e.getMessage());
            return null;
        }
    }
}
