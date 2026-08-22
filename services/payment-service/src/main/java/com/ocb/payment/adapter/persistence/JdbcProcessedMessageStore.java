package com.ocb.payment.adapter.persistence;

import com.ocb.payment.domain.port.ProcessedMessageStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedMessageStore implements ProcessedMessageStore {

    private final JdbcClient jdbc;

    public JdbcProcessedMessageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * L'insertion est la deduplication.
     *
     * <p>Pas de "SELECT puis INSERT" : entre les deux, un autre consommateur du meme
     * groupe pourrait traiter le meme message. La cle primaire fait le travail de maniere
     * atomique, et {@code ON CONFLICT DO NOTHING} transforme le doublon en zero ligne
     * inseree plutot qu'en erreur qui avorterait la transaction.
     */
    @Override
    public boolean markProcessed(String consumerGroup, String eventId, String eventType) {
        return jdbc.sql("""
                        INSERT INTO payment.processed_message (consumer_group, event_id, event_type)
                        VALUES (:group, :eventId, :eventType)
                        ON CONFLICT (consumer_group, event_id) DO NOTHING
                        """)
                .param("group", consumerGroup)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update() == 1;
    }
}
