package com.ocb.payment.adapter.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.ReceivedEvent;
import com.ocb.platform.events.Topics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Operateur simule. <b>Echafaudage de Phase 2, supprime en Phase 3.</b>
 *
 * <p>Il occupe la place exacte de {@code provider-service} : il consomme
 * {@code ocb.cmd.provider.v1} et publie sur {@code ocb.evt.provider.v1}. Quand le vrai
 * service arrivera, cette classe disparaitra sans qu'aucun contrat ne change — c'est
 * precisement ce que permet le decouplage par le bus.
 *
 * <p><b>Le comportement est pilote par le montant</b>, convention reellement utilisee par
 * les bacs a sable des prestataires de paiement : elle evite d'ajouter une API
 * d'administration juste pour tester, et rend les recettes de test reproductibles.
 *
 * <ul>
 *   <li>montant terminant par <b>98</b> — l'operateur refuse ;
 *   <li>montant terminant par <b>97</b> — l'operateur accepte puis ne conclut jamais
 *       (transaction laissee en attente, cas du timeout) ;
 *   <li>montant terminant par <b>96</b> — le succes est publie <b>deux fois</b>, pour
 *       verifier qu'un callback duplique est neutralise ;
 *   <li>tout autre montant — succes, avec une commission de 1,5 %.
 * </ul>
 *
 * <p>Ce simulateur publie directement, sans outbox : il represente un systeme externe, et
 * un systeme externe n'a pas de transaction commune avec nous. Lui donner une outbox
 * reviendrait a simuler une garantie que le vrai operateur n'offrira jamais.
 */
@Component
@ConditionalOnProperty(prefix = "ocb.provider.simulator", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class SimulatedProviderConsumer {

    private static final Logger log = LoggerFactory.getLogger(SimulatedProviderConsumer.class);
    private static final String GROUP = "provider-simulator";
    private static final String PRODUCER = "provider-simulator";

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper = EventJson.mapper();

    public SimulatedProviderConsumer(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @KafkaListener(topics = "#{T(com.ocb.platform.events.Topics).CMD_PROVIDER}",
            groupId = GROUP, containerFactory = "paymentKafkaListenerContainerFactory")
    public void onCommand(String rawMessage) throws Exception {
        ReceivedEvent event = mapper.readValue(rawMessage, ReceivedEvent.class);
        if (!EventTypes.PROVIDER_COLLECTION_EXECUTE.equals(event.eventType())) {
            return;
        }

        Payloads.ProviderCollectionExecute command =
                event.payloadAs(mapper, Payloads.ProviderCollectionExecute.class);

        String transactionId = command.transactionId();
        String providerRef = "SIM-" + transactionId.substring(0, 8);
        String correlationId = event.correlationId();

        publish(EventTypes.PROVIDER_OPERATION_ACCEPTED, transactionId, correlationId, event.eventId(),
                new Payloads.ProviderOperationAccepted(
                        transactionId, command.providerCode(), providerRef, Instant.now()));

        Behaviour behaviour = behaviourFor(command.amount());
        log.info("Operateur simule : transaction {}, comportement {}", transactionId, behaviour);

        switch (behaviour) {
            case DECLINE -> publish(EventTypes.PROVIDER_OPERATION_FAILED, transactionId, correlationId,
                    event.eventId(), new Payloads.ProviderOperationFailed(
                            transactionId, command.providerCode(), providerRef,
                            "INSUFFICIENT_FUNDS", "Solde insuffisant chez l'operateur", "CALLBACK"));

            case NEVER_CONCLUDE ->
                // Rien de plus. La transaction reste en attente, exactement comme lorsqu'un
                // operateur ne repond jamais. Elle ne doit surtout pas basculer en echec :
                // l'argent a peut-etre bouge. Le polling de la Phase 3 tranchera.
                    log.info("Operateur simule : aucune issue publiee pour {}", transactionId);

            case DUPLICATE_SUCCESS -> {
                Payloads.ProviderOperationSucceeded success = success(command, providerRef);
                publish(EventTypes.PROVIDER_OPERATION_SUCCEEDED, transactionId, correlationId,
                        event.eventId(), success);
                // Second envoi, avec un eventId different : ce n'est donc PAS un doublon
                // technique que la deduplication attraperait. C'est un doublon logique,
                // que seule la machine a etats peut neutraliser.
                publish(EventTypes.PROVIDER_OPERATION_SUCCEEDED, transactionId, correlationId,
                        event.eventId(), success);
            }

            case SUCCEED -> publish(EventTypes.PROVIDER_OPERATION_SUCCEEDED, transactionId,
                    correlationId, event.eventId(), success(command, providerRef));
        }
    }

    private Payloads.ProviderOperationSucceeded success(Payloads.ProviderCollectionExecute command,
                                                        String providerRef) {
        BigDecimal amount = new BigDecimal(command.amount());
        BigDecimal fee = amount.multiply(new BigDecimal("0.015"))
                .setScale(0, java.math.RoundingMode.HALF_UP);
        return new Payloads.ProviderOperationSucceeded(
                command.transactionId(), command.providerCode(), providerRef,
                fee.toPlainString(), command.currency(), Instant.now(), "CALLBACK");
    }

    private void publish(String eventType, String transactionId, String correlationId,
                         String causationId, Object payload) {
        try {
            EventEnvelope envelope = EventEnvelope.of(eventType, "ProviderOperation",
                    transactionId, correlationId, causationId, PRODUCER, payload);
            kafka.send(Topics.EVT_PROVIDER, transactionId, mapper.writeValueAsString(envelope));
        } catch (Exception e) {
            throw new IllegalStateException("Publication simulee en echec", e);
        }
    }

    private Behaviour behaviourFor(String amount) {
        String digits = amount.contains(".") ? amount.substring(0, amount.indexOf('.')) : amount;
        if (digits.length() < 2) {
            return Behaviour.SUCCEED;
        }
        return switch (digits.substring(digits.length() - 2)) {
            case "98" -> Behaviour.DECLINE;
            case "97" -> Behaviour.NEVER_CONCLUDE;
            case "96" -> Behaviour.DUPLICATE_SUCCESS;
            default -> Behaviour.SUCCEED;
        };
    }

    private enum Behaviour {
        SUCCEED, DECLINE, NEVER_CONCLUDE, DUPLICATE_SUCCESS
    }
}
