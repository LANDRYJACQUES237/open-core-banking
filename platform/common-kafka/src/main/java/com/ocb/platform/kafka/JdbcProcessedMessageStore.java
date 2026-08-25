package com.ocb.platform.kafka;

import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcProcessedMessageStore implements ProcessedMessageStore {

    private final JdbcClient jdbc;
    private final KafkaConsumerProperties properties;

    public JdbcProcessedMessageStore(JdbcClient jdbc, KafkaConsumerProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    /**
     * L'insertion est la deduplication.
     *
     * <p>Pas de "SELECT puis INSERT" : entre les deux, un autre consommateur du meme
     * groupe pourrait traiter le meme message. La cle primaire fait le travail de maniere
     * atomique, et {@code ON CONFLICT DO NOTHING} transforme le doublon en zero ligne
     * inseree plutot qu'en erreur qui avorterait la transaction — donc qui emporterait
     * aussi l'effet metier ecrit juste apres.
     *
     * <p>Le nom du schema est interpole plutot que parametre : un identifiant SQL ne peut
     * pas etre lie comme une valeur. Il vient de la configuration du service, jamais d'une
     * requete.
     */
    @Override
    public boolean markProcessed(String consumerGroup, String eventId, String eventType) {
        return jdbc.sql("""
                        INSERT INTO %s.processed_message (consumer_group, event_id, event_type)
                        VALUES (:group, :eventId, :eventType)
                        ON CONFLICT (consumer_group, event_id) DO NOTHING
                        """.formatted(properties.getSchema()))
                .param("group", consumerGroup)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update() == 1;
    }
}
