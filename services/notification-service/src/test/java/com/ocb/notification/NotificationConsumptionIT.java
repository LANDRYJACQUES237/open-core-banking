package com.ocb.notification;

import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * La consommation elle-meme : redelivrance, rebut, et tolerance aux types inconnus.
 *
 * <p>Ces quatre scenarios sont la raison d'etre de ce service dans le projet. Ils ne
 * peuvent pas etre etablis en appelant la couche application directement : ce qui est
 * eprouve ici est le comportement du <b>couple</b> consommateur-courtier.
 */
class NotificationConsumptionIT extends NotificationKafkaTestBase {

    @Test
    @DisplayName("un evenement de paiement produit une notification")
    void anEventBecomesANotification() {
        UUID transactionId = UUID.randomUUID();

        publish(collectionCompleted(transactionId, "evt-" + suffix), transactionId.toString());

        await().atMost(SETTLE_TIMEOUT)
                .untilAsserted(() -> assertThat(notificationCount(transactionId)).isEqualTo(1));
    }

    @Test
    @DisplayName("le meme evenement redelivre ne notifie qu'une fois")
    void redeliveryNotifiesOnlyOnce() {
        UUID transactionId = UUID.randomUUID();
        String eventId = "evt-double-" + suffix;

        // Meme identifiant d'evenement : c'est exactement ce que produit un redemarrage
        // entre le traitement d'un message et la validation de son decalage. Kafka livre
        // au moins une fois ; sans deduplication, le client serait prevenu deux fois.
        publish(collectionCompleted(transactionId, eventId), transactionId.toString());
        publish(collectionCompleted(transactionId, eventId), transactionId.toString());

        await().atMost(SETTLE_TIMEOUT)
                .untilAsserted(() -> assertThat(processedCount(eventId)).isEqualTo(1));

        // L'assertion negative demande une precaution : constater "toujours une seule
        // notification" juste apres la publication ne prouverait rien, puisque la seconde
        // n'a peut-etre pas encore ete traitee. Le compteur de messages traites, lui, a
        // deja atteint son etat final ci-dessus — le second message a donc bien ete vu et
        // ecarte.
        assertThat(notificationCount(transactionId))
                .as("le client n'est prevenu qu'une fois")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("un message definitivement illisible part au rebut sans bloquer la partition")
    void unreadableMessageGoesToTheDeadLetterTopic() {
        UUID transactionId = UUID.randomUUID();

        // Enveloppe valide, charge utile inexploitable : le message franchit la lecture de
        // l'enveloppe puis echoue a la deserialisation du contenu. C'est le cas qui compte
        // — un message illisible des l'enveloppe serait ecarte plus tot, sans jamais
        // atteindre le gestionnaire d'erreurs.
        publishRaw(transactionId.toString(), """
                {"eventId":"evt-casse-%s","eventType":"%s","eventVersion":1,\
                "occurredAt":"2026-08-25T10:00:00Z","aggregateType":"PaymentTransaction",\
                "aggregateId":"%s","correlationId":"corr-%s","producer":"payment-service",\
                "payload":{"transactionId":"pas-un-uuid","externalRef":"TX","amount":"10000",\
                "currency":"XAF","platformFee":"100","providerFee":"150",\
                "walletAccountRef":"2100.w","ledgerEntryRef":"JE-1"}}
                """.formatted(suffix, EventTypes.PAYMENT_COLLECTION_COMPLETED, transactionId, suffix));

        await().atMost(SETTLE_TIMEOUT).untilAsserted(() ->
                assertThat(deadLetterMessages())
                        .as("le rebut n'est pas une poubelle : c'est une file qu'un humain examine")
                        .anyMatch(message -> message.contains("evt-casse-" + suffix)));

        assertThat(notificationCount(transactionId))
                .as("rien n'a ete annonce a partir d'un message qu'on n'a pas su lire")
                .isZero();

        // La partition n'est pas bloquee : un message sain publie ensuite est traite.
        UUID next = UUID.randomUUID();
        publish(collectionCompleted(next, "evt-apres-" + suffix), transactionId.toString());
        await().atMost(SETTLE_TIMEOUT)
                .untilAsserted(() -> assertThat(notificationCount(next)).isEqualTo(1));
    }

    @Test
    @DisplayName("un type inconnu est acquitte sans effet et sans rebut")
    void unknownTypeIsAcknowledged() {
        UUID transactionId = UUID.randomUUID();
        String eventId = "evt-inconnu-" + suffix;

        publishRaw(transactionId.toString(), """
                {"eventId":"%s","eventType":"payment.something.new","eventVersion":1,\
                "occurredAt":"2026-08-25T10:00:00Z","aggregateType":"PaymentTransaction",\
                "aggregateId":"%s","correlationId":"corr-%s","producer":"payment-service",\
                "payload":{"transactionId":"%s"}}
                """.formatted(eventId, transactionId, suffix, transactionId));

        // Acquitte, donc marque comme traite : c'est ce qui permet d'ajouter un evenement
        // au topic sans transformer chaque ajout retrocompatible en panne de ce service.
        await().atMost(SETTLE_TIMEOUT)
                .untilAsserted(() -> assertThat(processedCount(eventId)).isEqualTo(1));

        assertThat(notificationCount(transactionId)).isZero();
        assertThat(deadLetterMessages())
                .as("ignorer n'est pas echouer")
                .noneMatch(message -> message.contains(eventId));
    }

    // --- Utilitaires -------------------------------------------------------------------

    /**
     * Enveloppe construite par son constructeur canonique et non par {@code of(...)}.
     *
     * <p>La fabrique tire un identifiant d'evenement au hasard, ce qui rendrait le test de
     * redelivrance impossible a ecrire : deux publications successives seraient deux
     * evenements distincts, et la deduplication n'aurait rien a reconnaitre.
     */
    private EventEnvelope collectionCompleted(UUID transactionId, String eventId) {
        return new EventEnvelope(
                eventId,
                EventTypes.PAYMENT_COLLECTION_COMPLETED,
                EventEnvelope.CURRENT_VERSION,
                java.time.Instant.now(),
                "PaymentTransaction",
                transactionId.toString(),
                "corr-" + suffix,
                null,
                "payment-service",
                new Payloads.PaymentCollectionCompleted(
                        transactionId.toString(), "TX-" + suffix, "10000", "XAF",
                        "100", "150", "2100.wallet-" + suffix, "JE-" + suffix, "+2376****0001"));
    }
}
