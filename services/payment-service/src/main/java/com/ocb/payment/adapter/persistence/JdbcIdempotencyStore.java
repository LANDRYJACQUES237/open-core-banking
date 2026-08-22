package com.ocb.payment.adapter.persistence;

import com.ocb.payment.domain.port.IdempotencyStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcIdempotencyStore implements IdempotencyStore {

    private final JdbcClient jdbc;

    public JdbcIdempotencyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserve la cle, ou rend ce qui a deja ete fait avec elle.
     *
     * <p>{@code ON CONFLICT DO NOTHING} plutot qu'un {@code try/catch} sur la violation
     * d'unicite : en PostgreSQL une erreur avorte la transaction entiere, et il deviendrait
     * impossible de relire l'enregistrement existant sans {@code SAVEPOINT}.
     *
     * <p>Le comportement sous concurrence est ce qui rend l'idempotence reellement sure :
     * si une transaction concurrente a insere la meme cle sans avoir encore valide, cet
     * {@code INSERT} <b>attend son issue</b> au lieu d'echouer. Deux requetes simultanees
     * portant la meme cle ne peuvent donc pas etre traitees toutes les deux.
     */
    @Override
    public Claim claim(String scope, String key, String requestHash) {
        int inserted = jdbc.sql("""
                        INSERT INTO payment.idempotency_record (id, scope, key, request_hash, status)
                        VALUES (:id, :scope, :key, :hash, 'IN_PROGRESS')
                        ON CONFLICT ON CONSTRAINT ux_idempotency_scope_key DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("scope", scope)
                .param("key", key)
                .param("hash", requestHash)
                .update();

        if (inserted == 1) {
            return Claim.fresh();
        }

        Optional<Existing> existing = jdbc.sql("""
                        SELECT request_hash, status, http_status, response_body::text AS response_body, resource_id
                          FROM payment.idempotency_record
                         WHERE scope = :scope AND key = :key
                        """)
                .param("scope", scope)
                .param("key", key)
                .query((rs, rowNum) -> new Existing(
                        rs.getString("request_hash"),
                        rs.getString("status"),
                        (Integer) rs.getObject("http_status"),
                        rs.getString("response_body"),
                        rs.getObject("resource_id", UUID.class)))
                .optional();

        if (existing.isEmpty()) {
            // La ligne concurrente a ete annulee entre-temps : l'appelant peut reessayer.
            return new Claim(Claim.Outcome.IN_PROGRESS, null, null, null);
        }

        Existing record = existing.get();
        if (!record.requestHash().equals(requestHash)) {
            return new Claim(Claim.Outcome.MISMATCH, null, null, null);
        }
        if (!"COMPLETED".equals(record.status())) {
            return new Claim(Claim.Outcome.IN_PROGRESS, null, null, null);
        }
        return new Claim(Claim.Outcome.REPLAY, record.httpStatus(), record.responseBody(), record.resourceId());
    }

    @Override
    public void complete(String scope, String key, int httpStatus, String responseBody, UUID resourceId) {
        jdbc.sql("""
                        UPDATE payment.idempotency_record
                           SET status = 'COMPLETED', http_status = :httpStatus,
                               response_body = CAST(:body AS jsonb), resource_id = :resourceId,
                               completed_at = now()
                         WHERE scope = :scope AND key = :key
                        """)
                .param("scope", scope)
                .param("key", key)
                .param("httpStatus", httpStatus)
                .param("body", responseBody)
                .param("resourceId", resourceId)
                .update();
    }

    private record Existing(String requestHash, String status, Integer httpStatus,
                            String responseBody, UUID resourceId) {
    }
}
