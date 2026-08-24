package com.ocb.provider.adapter.persistence;

import com.ocb.provider.domain.port.ProcessedMessageStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedMessageStore implements ProcessedMessageStore {

    private final JdbcClient jdbc;

    public JdbcProcessedMessageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(String consumerGroup, String eventId, String eventType) {
        return jdbc.sql("""
                        INSERT INTO provider.processed_message (consumer_group, event_id, event_type)
                        VALUES (:group, :eventId, :eventType)
                        ON CONFLICT (consumer_group, event_id) DO NOTHING
                        """)
                .param("group", consumerGroup)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update() == 1;
    }
}
