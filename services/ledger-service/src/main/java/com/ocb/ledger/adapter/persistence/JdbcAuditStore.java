package com.ocb.ledger.adapter.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.ledger.domain.AuditEvent;
import com.ocb.ledger.domain.port.AuditStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Journal d'audit en insertion seule, avec chainage de hachage differe.
 *
 * <p>Le chainage rend toute modification retroactive detectable : le hachage d'une entree
 * couvre son contenu et le hachage de la precedente, donc alterer une ligne invalide
 * toutes les suivantes. Un attaquant devrait recalculer la chaine entiere, ce qui suppose
 * des droits que le role applicatif n'a pas.
 */
@Repository
public class JdbcAuditStore implements AuditStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcAuditStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql("""
                        INSERT INTO ledger.audit_log
                            (id, actor_type, actor_id, action, resource_type, resource_id,
                             correlation_id, payload)
                        VALUES
                            (:id, :actorType, :actorId, :action, :resourceType, :resourceId,
                             :correlationId, CAST(:payload AS jsonb))
                        """)
                .param("id", UUID.randomUUID())
                .param("actorType", event.actorType())
                .param("actorId", event.actorId())
                .param("action", event.action())
                .param("resourceType", event.resourceType())
                .param("resourceId", event.resourceId())
                .param("correlationId", event.correlationId())
                .param("payload", toJson(event))
                .update();
    }

    @Override
    @Transactional
    public int sealPending() {
        String previousHash = jdbc
                .sql("SELECT hash FROM ledger.audit_seal ORDER BY audit_seq DESC LIMIT 1")
                .query(String.class)
                .optional()
                .orElse(null);

        List<Entry> pending = jdbc.sql("""
                        SELECT l.seq, l.occurred_at, l.actor_type, l.actor_id, l.action,
                               l.resource_type, l.resource_id, COALESCE(l.payload::text, '') AS payload
                          FROM ledger.audit_log l
                          LEFT JOIN ledger.audit_seal s ON s.audit_seq = l.seq
                         WHERE s.audit_seq IS NULL
                         ORDER BY l.seq
                        """)
                .query(ENTRY_MAPPER)
                .list();

        for (Entry entry : pending) {
            String hash = hash(entry, previousHash);
            jdbc.sql("""
                            INSERT INTO ledger.audit_seal (audit_seq, prev_hash, hash)
                            VALUES (:seq, :prev, :hash)
                            """)
                    .param("seq", entry.seq())
                    .param("prev", previousHash)
                    .param("hash", hash)
                    .update();
            previousHash = hash;
        }
        return pending.size();
    }

    @Override
    public List<ChainBreak> verifyChain() {
        List<Sealed> sealed = jdbc.sql("""
                        SELECT l.seq, l.occurred_at, l.actor_type, l.actor_id, l.action,
                               l.resource_type, l.resource_id, COALESCE(l.payload::text, '') AS payload,
                               s.prev_hash, s.hash
                          FROM ledger.audit_seal s
                          JOIN ledger.audit_log l ON l.seq = s.audit_seq
                         ORDER BY s.audit_seq
                        """)
                .query((rs, rowNum) -> new Sealed(
                        ENTRY_MAPPER.mapRow(rs, rowNum),
                        rs.getString("prev_hash"),
                        rs.getString("hash")))
                .list();

        List<ChainBreak> breaks = new ArrayList<>();
        String expectedPrevious = null;
        for (Sealed s : sealed) {
            if (!java.util.Objects.equals(expectedPrevious, s.prevHash())) {
                breaks.add(new ChainBreak(s.entry().seq(),
                        "maillon precedent incoherent : la chaine a ete rompue en amont"));
            }
            String recomputed = hash(s.entry(), s.prevHash());
            if (!recomputed.equals(s.hash())) {
                breaks.add(new ChainBreak(s.entry().seq(),
                        "contenu modifie apres scellement : le hachage recalcule ne correspond pas"));
            }
            expectedPrevious = s.hash();
        }
        return breaks;
    }

    private String toJson(AuditEvent event) {
        if (event.payload() == null || event.payload().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(event.payload());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Charge utile d'audit non serialisable", e);
        }
    }

    /**
     * Forme canonique hachee.
     *
     * <p>L'horodatage passe par {@code toInstant()} : deux lectures de la meme ligne
     * peuvent restituer des decalages horaires differents selon la session, ce qui
     * ferait diverger le hachage sans qu'aucune donnee n'ait change.
     */
    private static String hash(Entry e, String previousHash) {
        String canonical = String.join("|",
                Long.toString(e.seq()),
                e.occurredAt().toInstant().toString(),
                e.actorType(), e.actorId(), e.action(),
                e.resourceType(), e.resourceId(),
                e.payload(),
                previousHash == null ? "" : previousHash);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e2) {
            throw new IllegalStateException("SHA-256 indisponible", e2);
        }
    }

    private static final org.springframework.jdbc.core.RowMapper<Entry> ENTRY_MAPPER =
            (rs, rowNum) -> new Entry(
                    rs.getLong("seq"),
                    rs.getObject("occurred_at", OffsetDateTime.class),
                    rs.getString("actor_type"),
                    rs.getString("actor_id"),
                    rs.getString("action"),
                    rs.getString("resource_type"),
                    rs.getString("resource_id"),
                    rs.getString("payload"));

    private record Entry(long seq, OffsetDateTime occurredAt, String actorType, String actorId,
                         String action, String resourceType, String resourceId, String payload) {
    }

    private record Sealed(Entry entry, String prevHash, String hash) {
    }
}
