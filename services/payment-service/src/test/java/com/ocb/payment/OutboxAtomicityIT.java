package com.ocb.payment;

import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.Topics;
import com.ocb.platform.outbox.OutboxWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'atomicite de l'outbox : le point d'architecture central de cette phase.
 *
 * <p>Ce que le pattern supprime, en trois lignes :
 *
 * <pre>{@code
 * repository.save(transaction);   // commit OK
 * kafka.send(evenement);          // echoue -> evenement perdu, personne ne le saura
 * }</pre>
 *
 * <p>La base a valide, la publication a echoue, et rien ne garde trace du fait que
 * l'evenement aurait du partir. Les tests ci-dessous verifient la propriete qui remplace
 * cette esperance : l'evenement et la donnee metier vivent dans la meme transaction, donc
 * soit les deux existent, soit aucun.
 *
 * <p>Les frais fixes sont pousses tres haut ici pour disposer d'une cause de rejet
 * metier survenant <b>apres</b> le debut du traitement, et pouvoir observer ce que la
 * transaction annulee laisse — c'est-a-dire rien.
 */
@TestPropertySource(properties = {
        "ocb.fees.collection.fixed=1000000",
        "ocb.fees.collection.basis-points=0"
})
class OutboxAtomicityIT extends PaymentPersistenceTestBase {

    @Autowired
    private OutboxWriter outboxWriter;

    @Test
    @DisplayName("une demande rejetee ne laisse ni transaction ni evenement")
    void rejectedRequestLeavesNothing() {
        long transactionsBefore = transactionCount();
        long commandsBefore = totalOutboxCount("provider.collection.execute");

        // Les frais absorberaient la totalite du montant : refus metier.
        ApiResponse response = post("/v1/collections", "rollback-" + suffix, collectionBody("500"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("PAYMENT_INVALID_AMOUNT");

        // Le point du test : l'annulation est totale. Une implementation qui publierait
        // avant de valider aurait deja envoye une commande a l'operateur pour une demande
        // finalement refusee.
        assertThat(transactionCount())
                .as("aucune transaction ne subsiste")
                .isEqualTo(transactionsBefore);
        assertThat(totalOutboxCount("provider.collection.execute"))
                .as("aucune commande operateur ne subsiste")
                .isEqualTo(commandsBefore);
    }

    @Test
    @DisplayName("ecrire dans l'outbox hors transaction est refuse")
    void writingOutsideATransactionIsRefused() {
        // Sans transaction active, l'insertion serait auto-commitee independamment de la
        // donnee metier : on retomberait exactement sur le dual-write que l'outbox existe
        // pour supprimer. Mieux vaut echouer bruyamment au developpement que produire un
        // systeme qui parait correct.
        EventEnvelope envelope = EventEnvelope.of(
                "payment.collection.requested", "PaymentTransaction",
                "00000000-0000-0000-0000-000000000001", null, null, "payment-service",
                java.util.Map.of("test", true));

        assertThatThrownBy(() -> outboxWriter.append(Topics.EVT_PAYMENT, "k", envelope))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    @Test
    @DisplayName("une demande acceptee depose ses evenements avec la transaction, non publies")
    void acceptedRequestStoresEventsPending() {
        // Frais raisonnables pour ce test uniquement : on repasse par une demande valide.
        ApiResponse response = post("/v1/collections", "pending-" + suffix,
                collectionBody("100000000"));
        assertThat(response.status()).isEqualTo(202);

        List<PendingRow> pending = jdbc.sql("""
                        SELECT event_type, topic, partition_key, published_at, event_id, payload::text AS payload
                          FROM payment.outbox_event
                         WHERE aggregate_id = :id
                         ORDER BY seq
                        """)
                .param("id", response.transactionId())
                .query((rs, rowNum) -> new PendingRow(
                        rs.getString("event_type"), rs.getString("topic"),
                        rs.getString("partition_key"), rs.getObject("published_at") != null,
                        rs.getString("event_id"), rs.getString("payload")))
                .list();

        assertThat(pending).hasSize(2);
        assertThat(pending).extracting(PendingRow::eventType)
                .containsExactly("provider.collection.execute", "payment.collection.requested");

        // Le relais est desactive dans ces tests : les evenements sont ecrits, pas publies.
        assertThat(pending).allSatisfy(row ->
                assertThat(row.published()).as("aucun evenement ne doit etre publie ici").isFalse());

        // La cle de partition est l'identifiant de la transaction : c'est ce qui garantit
        // que ses evenements arrivent dans l'ordre chez le consommateur.
        assertThat(pending).allSatisfy(row ->
                assertThat(row.partitionKey()).isEqualTo(response.transactionId()));

        assertThat(pending.get(0).topic()).isEqualTo(Topics.CMD_PROVIDER);
        assertThat(pending.get(1).topic()).isEqualTo(Topics.EVT_PAYMENT);
    }

    @Test
    @DisplayName("le numero complet ne circule que dans la commande operateur")
    void fullMsisdnOnlyInProviderCommand() throws Exception {
        ApiResponse response = post("/v1/collections", "privacy-" + suffix,
                collectionBody("100000000", "+237670000009"));
        assertThat(response.status()).isEqualTo(202);

        String command = payloadOf(response.transactionId(), "provider.collection.execute");
        String event = payloadOf(response.transactionId(), "payment.collection.requested");

        // L'adaptateur operateur en a besoin pour appeler l'operateur : il y figure.
        assertThat(command).contains("+237670000009");

        // Partout ailleurs, seule la forme masquee circule.
        assertThat(event).doesNotContain("+237670000009");
        assertThat(event).contains("+2376****0009");

        // Et la transaction persistee ne le conserve pas du tout : une donnee absente ne
        // fuite pas, ne part pas dans un export et ne demande aucune gestion de cle.
        String stored = jdbc.sql("""
                        SELECT COALESCE(masked_msisdn, '') FROM payment.payment_transaction WHERE id = :id
                        """)
                .param("id", java.util.UUID.fromString(response.transactionId()))
                .query(String.class).single();
        assertThat(stored).isEqualTo("+2376****0009");
    }

    @Test
    @DisplayName("l'enveloppe stockee respecte le contrat d'evenement")
    void storedEnvelopeIsWellFormed() throws Exception {
        ApiResponse response = post("/v1/collections", "envelope-" + suffix,
                collectionBody("100000000"));

        String raw = payloadOf(response.transactionId(), "payment.collection.requested");
        var envelope = EventJson.mapper().readTree(raw);

        assertThat(envelope.get("eventId").asText()).isNotBlank();
        assertThat(envelope.get("eventType").asText()).isEqualTo("payment.collection.requested");
        assertThat(envelope.get("eventVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(response.transactionId());
        assertThat(envelope.get("producer").asText()).isEqualTo("payment-service");
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();
        // Les montants voyagent en chaine : un nombre JSON serait parse en double par de
        // nombreux clients, ce qui detruirait la precision.
        assertThat(envelope.get("payload").get("amount").isTextual()).isTrue();
    }

    private String payloadOf(String transactionId, String eventType) {
        return jdbc.sql("""
                        SELECT payload::text FROM payment.outbox_event
                         WHERE aggregate_id = :id AND event_type = :type
                        """)
                .param("id", transactionId)
                .param("type", eventType)
                .query(String.class)
                .single();
    }

    private record PendingRow(String eventType, String topic, String partitionKey,
                              boolean published, String eventId, String payload) {
    }
}
