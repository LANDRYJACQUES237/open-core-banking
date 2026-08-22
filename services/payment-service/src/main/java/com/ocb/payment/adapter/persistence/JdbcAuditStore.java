package com.ocb.payment.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.payment.domain.port.AuditStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.UUID;

@Repository
public class JdbcAuditStore implements AuditStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(String action, String resourceType, String resourceId,
                       String correlationId, Map<String, Object> payload) {
        jdbc.sql("""
                        INSERT INTO payment.audit_log
                            (id, actor_type, actor_id, action, resource_type, resource_id,
                             correlation_id, payload)
                        VALUES
                            (:id, 'SERVICE', 'payment-service', :action, :resourceType, :resourceId,
                             :correlationId, CAST(:payload AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("action", action)
                .param("resourceType", resourceType)
                .param("resourceId", resourceId)
                .param("correlationId", correlationId)
                .param("payload", toJson(payload))
                .update();
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Charge utile d'audit non serialisable", e);
        }
    }
}
