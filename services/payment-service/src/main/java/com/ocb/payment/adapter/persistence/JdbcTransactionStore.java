package com.ocb.payment.adapter.persistence;

import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.ProviderCode;
import com.ocb.payment.domain.StateTransitionRecord;
import com.ocb.payment.domain.TransactionStatus;
import com.ocb.payment.domain.TransactionType;
import com.ocb.payment.domain.TransactionUpdate;
import com.ocb.payment.domain.port.TransactionStore;
import com.ocb.platform.domain.money.Money;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcTransactionStore implements TransactionStore {

    private static final String SELECT = """
            SELECT id, external_ref, type, status, amount, currency, platform_fee, provider_fee,
                   wallet_account_ref, provider_code, masked_msisdn, provider_ref,
                   ledger_entry_ref, failure_code, failure_reason, created_at, updated_at, version
              FROM payment.payment_transaction
            """;

    private final JdbcClient jdbc;

    public JdbcTransactionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PaymentTransaction create(PaymentTransaction t) {
        jdbc.sql("""
                        INSERT INTO payment.payment_transaction
                            (id, external_ref, type, status, amount, currency, platform_fee,
                             wallet_account_ref, provider_code, masked_msisdn)
                        VALUES
                            (:id, :externalRef, :type, :status, :amount, :currency, :platformFee,
                             :walletAccountRef, :providerCode, :maskedMsisdn)
                        """)
                .param("id", t.id())
                .param("externalRef", t.externalRef())
                .param("type", t.type().name())
                .param("status", t.status().name())
                .param("amount", t.amount().amount())
                .param("currency", t.amount().currencyCode())
                .param("platformFee", t.platformFee().amount())
                .param("walletAccountRef", t.walletAccountRef())
                .param("providerCode", t.providerCode().name())
                .param("maskedMsisdn", t.maskedMsisdn())
                .update();
        return find(t.id()).orElseThrow();
    }

    @Override
    public Optional<PaymentTransaction> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(MAPPER).optional();
    }

    @Override
    public Optional<PaymentTransaction> lockById(UUID id) {
        // FOR UPDATE sans SKIP LOCKED ni NOWAIT : un appelant concurrent doit ATTENDRE,
        // pas abandonner. C'est exactement ce qu'on veut pour la course entre un callback
        // operateur et le poller de reconciliation — le second doit voir l'etat que le
        // premier a produit, et se faire refuser par la machine a etats.
        return jdbc.sql(SELECT + " WHERE id = :id FOR UPDATE")
                .param("id", id).query(MAPPER).optional();
    }

    @Override
    public PaymentTransaction applyTransition(UUID id, TransactionStatus target, TransactionUpdate update) {
        jdbc.sql("""
                        UPDATE payment.payment_transaction
                           SET status           = :status,
                               provider_ref     = COALESCE(:providerRef, provider_ref),
                               provider_fee     = COALESCE(:providerFee, provider_fee),
                               ledger_entry_ref = COALESCE(:ledgerEntryRef, ledger_entry_ref),
                               failure_code     = COALESCE(:failureCode, failure_code),
                               failure_reason   = COALESCE(:failureReason, failure_reason),
                               updated_at       = now(),
                               version          = version + 1
                         WHERE id = :id
                        """)
                .param("id", id)
                .param("status", target.name())
                .param("providerRef", update.providerRef())
                .param("providerFee", update.providerFee() == null ? null : update.providerFee().amount())
                .param("ledgerEntryRef", update.ledgerEntryRef())
                .param("failureCode", update.failureCode())
                .param("failureReason", update.failureReason())
                .update();
        return find(id).orElseThrow();
    }

    @Override
    public void recordTransition(UUID transactionId, TransactionStatus from, TransactionStatus to,
                                 String triggerEvent, boolean accepted, String rejectionReason,
                                 String correlationId) {
        jdbc.sql("""
                        INSERT INTO payment.transaction_state_transition
                            (id, transaction_id, from_status, to_status, trigger_event,
                             accepted, rejection_reason, correlation_id)
                        VALUES
                            (:id, :transactionId, :from, :to, :trigger,
                             :accepted, :reason, :correlationId)
                        """)
                .param("id", UUID.randomUUID())
                .param("transactionId", transactionId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("trigger", triggerEvent)
                .param("accepted", accepted)
                .param("reason", rejectionReason)
                .param("correlationId", correlationId)
                .update();
    }

    @Override
    public List<StateTransitionRecord> transitionsOf(UUID transactionId) {
        return jdbc.sql("""
                        SELECT seq, from_status, to_status, trigger_event, accepted,
                               rejection_reason, occurred_at
                          FROM payment.transaction_state_transition
                         WHERE transaction_id = :id
                         ORDER BY seq
                        """)
                .param("id", transactionId)
                .query((rs, rowNum) -> new StateTransitionRecord(
                        rs.getLong("seq"),
                        rs.getString("from_status") == null
                                ? null : TransactionStatus.valueOf(rs.getString("from_status")),
                        TransactionStatus.valueOf(rs.getString("to_status")),
                        rs.getString("trigger_event"),
                        rs.getBoolean("accepted"),
                        rs.getString("rejection_reason"),
                        rs.getObject("occurred_at", OffsetDateTime.class)))
                .list();
    }

    private static final RowMapper<PaymentTransaction> MAPPER = JdbcTransactionStore::map;

    private static PaymentTransaction map(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency").trim();
        BigDecimal providerFee = rs.getBigDecimal("provider_fee");
        return new PaymentTransaction(
                rs.getObject("id", UUID.class),
                rs.getString("external_ref"),
                TransactionType.valueOf(rs.getString("type")),
                TransactionStatus.valueOf(rs.getString("status")),
                Money.of(rs.getBigDecimal("amount"), currency),
                Money.of(rs.getBigDecimal("platform_fee"), currency),
                providerFee == null ? null : Money.of(providerFee, currency),
                rs.getString("wallet_account_ref"),
                ProviderCode.valueOf(rs.getString("provider_code")),
                rs.getString("masked_msisdn"),
                rs.getString("provider_ref"),
                rs.getString("ledger_entry_ref"),
                rs.getString("failure_code"),
                rs.getString("failure_reason"),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("updated_at", OffsetDateTime.class),
                rs.getLong("version"));
    }
}
