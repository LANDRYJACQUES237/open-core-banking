package com.ocb.provider.adapter.persistence;

import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.OperationType;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.ProviderOperation;
import com.ocb.provider.domain.port.OperationStore;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcOperationStore implements OperationStore {

    private static final String SELECT = """
            SELECT id, transaction_id, provider_code, operation_type, external_ref,
                   provider_idempotency_key, payer_msisdn, amount, currency, provider_ref,
                   status, provider_fee, error_code, error_message, last_error,
                   attempt_count, poll_attempts, last_polled_at, next_poll_at,
                   poll_budget_exhausted, created_at, updated_at, version
              FROM provider.provider_operation
            """;

    private final JdbcClient jdbc;

    public JdbcOperationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Created createOrGet(ProviderOperation operation) {
        // ON CONFLICT DO NOTHING plutot qu'un try/catch : en PostgreSQL une erreur avorte
        // la transaction entiere, et il deviendrait impossible de relire l'operation
        // existante. Sous concurrence, l'insertion attend l'issue de la transaction qui
        // detient deja la cle plutot que d'echouer — c'est ce qui rend le garde-fou sur
        // (provider_code, transaction_id) reellement sur.
        int inserted = jdbc.sql("""
                        INSERT INTO provider.provider_operation
                            (id, transaction_id, provider_code, operation_type, external_ref,
                             provider_idempotency_key, payer_msisdn, amount, currency,
                             status, attempt_count, next_poll_at)
                        VALUES
                            (:id, :transactionId, :providerCode, :type, :externalRef,
                             :idempotencyKey, :msisdn, :amount, :currency,
                             :status, 1, :nextPollAt)
                        ON CONFLICT ON CONSTRAINT ux_operation_transaction DO NOTHING
                        """)
                .param("id", operation.id())
                .param("transactionId", operation.transactionId())
                .param("providerCode", operation.providerCode().name())
                .param("type", operation.type().name())
                .param("externalRef", operation.externalRef())
                .param("idempotencyKey", operation.providerIdempotencyKey())
                .param("msisdn", operation.payerMsisdn())
                .param("amount", operation.amount().amount())
                .param("currency", operation.amount().currencyCode())
                .param("status", operation.status().name())
                .param("nextPollAt", toOffset(operation.nextPollAt()))
                .update();

        ProviderOperation stored = findByTransaction(operation.providerCode(), operation.transactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Operation introuvable apres insertion : " + operation.transactionId()));
        return new Created(stored, inserted == 1);
    }

    @Override
    public Optional<ProviderOperation> findByTransaction(ProviderCode providerCode, UUID transactionId) {
        return jdbc.sql(SELECT + " WHERE provider_code = :code AND transaction_id = :id")
                .param("code", providerCode.name())
                .param("id", transactionId)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<ProviderOperation> findByExternalRef(String externalRef) {
        return jdbc.sql(SELECT + " WHERE external_ref = :ref")
                .param("ref", externalRef)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<ProviderOperation> lockById(UUID id) {
        // FOR UPDATE sans SKIP LOCKED : un rappel et une relance qui visent la meme
        // operation doivent se serialiser, pas s'ignorer.
        return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    @Override
    public ProviderOperation markAccepted(UUID id, String providerRef, Instant nextPollAt) {
        jdbc.sql("""
                        UPDATE provider.provider_operation
                           SET status = 'ACCEPTED', provider_ref = COALESCE(:ref, provider_ref),
                               next_poll_at = :nextPollAt, updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("ref", providerRef)
                .param("nextPollAt", toOffset(nextPollAt))
                .update();
        return reload(id);
    }

    @Override
    public ProviderOperation markResolved(UUID id, OperationStatus status, String providerRef,
                                          Money fee, String errorCode, String errorMessage) {
        // next_poll_at passe a NULL : c'est ainsi qu'un rappel annule les relances
        // restantes. L'ordonnanceur ne voit plus l'operation, sans qu'aucun code
        // d'annulation explicite n'ait ete necessaire.
        jdbc.sql("""
                        UPDATE provider.provider_operation
                           SET status = :status,
                               provider_ref = COALESCE(:ref, provider_ref),
                               provider_fee = COALESCE(:fee, provider_fee),
                               error_code = COALESCE(:errorCode, error_code),
                               error_message = COALESCE(:errorMessage, error_message),
                               next_poll_at = NULL,
                               updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("ref", providerRef)
                .param("fee", fee == null ? null : fee.amount())
                .param("errorCode", errorCode)
                .param("errorMessage", errorMessage)
                .update();
        return reload(id);
    }

    @Override
    public ProviderOperation recordAttempt(UUID id, int pollAttempts, Instant lastPolledAt,
                                           Instant nextPollAt, String lastError) {
        jdbc.sql("""
                        UPDATE provider.provider_operation
                           SET poll_attempts = :pollAttempts, attempt_count = attempt_count + 1,
                               last_polled_at = :lastPolledAt, next_poll_at = :nextPollAt,
                               last_error = :lastError, updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("pollAttempts", pollAttempts)
                .param("lastPolledAt", toOffset(lastPolledAt))
                .param("nextPollAt", toOffset(nextPollAt))
                .param("lastError", truncate(lastError))
                .update();
        return reload(id);
    }

    @Override
    public ProviderOperation markBudgetExhausted(UUID id, String lastError) {
        jdbc.sql("""
                        UPDATE provider.provider_operation
                           SET status = 'UNRESOLVED', poll_budget_exhausted = true,
                               next_poll_at = NULL, last_error = COALESCE(:lastError, last_error),
                               updated_at = now(), version = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("lastError", truncate(lastError))
                .update();
        return reload(id);
    }

    @Override
    public List<ProviderOperation> lockDueForPolling(Instant now, int limit) {
        // SKIP LOCKED : deux instances de l'ordonnanceur peuvent tourner sans se disputer
        // les memes lignes. Contrairement a l'outbox, l'ordre n'a ici aucune importance —
        // deux relances d'operations differentes sont independantes.
        return jdbc.sql(SELECT + """
                         WHERE next_poll_at IS NOT NULL
                           AND next_poll_at <= :now
                           AND status IN ('PENDING', 'ACCEPTED')
                           AND poll_budget_exhausted = false
                         ORDER BY next_poll_at
                         FOR UPDATE SKIP LOCKED
                         LIMIT :limit
                        """)
                .param("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC))
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    private ProviderOperation reload(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(MAPPER).single();
    }

    private static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private static final RowMapper<ProviderOperation> MAPPER = JdbcOperationStore::map;

    private static ProviderOperation map(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency").trim();
        BigDecimal fee = rs.getBigDecimal("provider_fee");
        return new ProviderOperation(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                ProviderCode.valueOf(rs.getString("provider_code")),
                OperationType.valueOf(rs.getString("operation_type")),
                rs.getString("external_ref"),
                rs.getString("provider_idempotency_key"),
                rs.getString("payer_msisdn"),
                Money.of(rs.getBigDecimal("amount"), currency),
                rs.getString("provider_ref"),
                OperationStatus.valueOf(rs.getString("status")),
                fee == null ? null : Money.of(fee, currency),
                rs.getString("error_code"),
                rs.getString("error_message"),
                rs.getString("last_error"),
                rs.getInt("attempt_count"),
                rs.getInt("poll_attempts"),
                instant(rs, "last_polled_at"),
                instant(rs, "next_poll_at"),
                rs.getBoolean("poll_budget_exhausted"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getLong("version"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
