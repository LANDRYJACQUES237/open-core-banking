package com.ocb.platform.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * Ecrit un evenement dans l'outbox, <b>dans la transaction en cours</b>.
 *
 * <p>Le piege que ce pattern supprime tient en trois lignes :
 *
 * <pre>{@code
 * @Transactional
 * void traiter() {
 *     repository.save(transaction);   // commit OK
 *     kafka.send(evenement);          // echoue -> evenement perdu, personne ne le saura
 * }
 * }</pre>
 *
 * <p>La base a valide, la publication a echoue, et il n'existe aucune trace du fait que
 * l'evenement aurait du partir. Le systeme est incoherent et silencieux. Inverser l'ordre
 * ne fait que deplacer le probleme : la publication reussit, le commit echoue, et un
 * evenement annonce un fait qui ne s'est jamais produit.
 *
 * <p>La solution consiste a n'ecrire que dans un seul systeme. L'evenement va dans une
 * table de la <b>meme base</b>, donc dans la <b>meme transaction</b> : soit les deux
 * existent, soit aucun. Un relais separe se charge ensuite de la publication
 * ({@link OutboxRelay}).
 *
 * <p>L'{@code eventId} est attribue ici, a l'ecriture, et non a la publication. C'est ce
 * qui rend la republication inoffensive : un evenement republie apres un incident porte le
 * meme identifiant, donc les consommateurs le reconnaissent comme un doublon.
 */
public class OutboxWriter {

    private final JdbcClient jdbc;
    private final OutboxProperties properties;

    public OutboxWriter(JdbcClient jdbc, OutboxProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * @param topic        topic de destination
     * @param partitionKey cle de partition. Toujours l'identifiant de l'agregat : c'est ce
     *                     qui garantit que deux evenements d'une meme transaction arrivent
     *                     dans l'ordre chez le consommateur
     */
    public void append(String topic, String partitionKey, EventEnvelope envelope) {
        // Sans transaction active, l'insertion serait auto-commitee independamment de la
        // donnee metier : on retomberait exactement sur le dual-write que l'outbox existe
        // pour supprimer. Mieux vaut echouer bruyamment au developpement.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxWriter.append doit etre appele dans une transaction : sinon l'evenement "
                            + "et la donnee metier ne sont plus atomiques, ce qui vide le pattern de son sens");
        }

        jdbc.sql("""
                        INSERT INTO %s.outbox_event
                            (id, event_id, aggregate_type, aggregate_id, event_type,
                             topic, partition_key, payload, headers)
                        VALUES
                            (:id, :eventId, :aggregateType, :aggregateId, :eventType,
                             :topic, :partitionKey, CAST(:payload AS jsonb), CAST(:headers AS jsonb))
                        """.formatted(properties.getSchema()))
                .param("id", UUID.randomUUID())
                .param("eventId", envelope.eventId())
                .param("aggregateType", envelope.aggregateType())
                .param("aggregateId", envelope.aggregateId())
                .param("eventType", envelope.eventType())
                .param("topic", topic)
                .param("partitionKey", partitionKey)
                .param("payload", serialize(envelope))
                .param("headers", headers(envelope))
                .update();
    }

    private String serialize(EventEnvelope envelope) {
        try {
            return EventJson.mapper().writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            // Un evenement non serialisable est un bug de programmation, pas une panne :
            // il doit faire echouer la transaction metier plutot que d'etre avale.
            throw new IllegalArgumentException(
                    "Evenement %s non serialisable".formatted(envelope.eventType()), e);
        }
    }

    private String headers(EventEnvelope envelope) {
        try {
            return EventJson.mapper().writeValueAsString(java.util.Map.of(
                    "ce_id", envelope.eventId(),
                    "ce_type", envelope.eventType(),
                    "ce_source", envelope.producer()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
