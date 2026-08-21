package com.ocb.ledger.adapter.persistence;

import com.ocb.ledger.domain.AccountStatus;
import com.ocb.ledger.domain.AccountType;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.LedgerErrors;
import com.ocb.ledger.domain.port.AccountStore;
import com.ocb.platform.domain.error.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAccountStore implements AccountStore {

    private static final String SELECT = """
            SELECT id, account_number, account_type, currency, owner_ref, name,
                   status, is_postable, parent_id, opened_at
              FROM ledger.account
            """;

    private final JdbcClient jdbc;

    public JdbcAccountStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LedgerAccount> findByNumber(String accountNumber) {
        return jdbc.sql(SELECT + " WHERE account_number = :n")
                .param("n", accountNumber)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<LedgerAccount> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT + " WHERE idempotency_key = :k")
                .param("k", idempotencyKey)
                .query(MAPPER)
                .optional();
    }

    @Override
    public Map<String, LedgerAccount> findByNumbers(java.util.Collection<String> accountNumbers) {
        if (accountNumbers.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql(SELECT + " WHERE account_number IN (:numbers)")
                .param("numbers", accountNumbers)
                .query(MAPPER)
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(LedgerAccount::accountNumber, a -> a));
    }

    @Override
    public Opened open(LedgerAccount account, String idempotencyKey) {
        // ON CONFLICT DO NOTHING plutot qu'un try/catch : en PostgreSQL, une erreur
        // avorte la transaction entiere. Il deviendrait impossible de relire l'existant
        // sans SAVEPOINT. Ici, un conflit ne produit simplement aucune ligne, et la
        // transaction reste utilisable.
        //
        // Effet de bord utile : si une transaction concurrente insere la meme cle sans
        // avoir encore valide, l'INSERT attend son issue au lieu d'echouer. C'est la
        // serialisation qui rend l'idempotence correcte sous concurrence.
        int inserted;
        try {
            inserted = jdbc.sql("""
                            INSERT INTO ledger.account (id, account_number, account_type, normal_side, currency,
                                                        owner_ref, name, status, is_postable, parent_id, idempotency_key)
                            VALUES (:id, :number, :type, :side, :currency,
                                    :ownerRef, :name, :status, :postable, :parentId, :key)
                            ON CONFLICT ON CONSTRAINT ux_account_idempotency_key DO NOTHING
                            """)
                    .param("id", account.id())
                    .param("number", account.accountNumber())
                    .param("type", account.type().name())
                    .param("side", account.normalSide().name())
                    .param("currency", account.currency().getCurrencyCode())
                    .param("ownerRef", account.ownerRef())
                    .param("name", account.name())
                    .param("status", account.status().name())
                    .param("postable", account.postable())
                    .param("parentId", account.parentId())
                    .param("key", idempotencyKey)
                    .update();
        } catch (DuplicateKeyException e) {
            // Le numero de compte est pris, avec une cle d'idempotence differente :
            // deux ouvertures concurrentes du meme numero. La couche application verifie
            // en amont, ce chemin ne se produit que sous course.
            if ("ux_account_number".equals(PostgresErrors.constraintName(e))) {
                throw new ConflictException(
                        LedgerErrors.ACCOUNT_NUMBER_TAKEN,
                        "Le compte %s existe deja".formatted(account.accountNumber()));
            }
            throw e;
        }

        if (inserted == 1) {
            return new Opened(account, true);
        }

        LedgerAccount existing = findByIdempotencyKey(idempotencyKey).orElseThrow(() ->
                new IllegalStateException(
                        "Conflit sur la cle d'idempotence sans ligne correspondante : " + idempotencyKey));
        return new Opened(existing, false);
    }

    private static final RowMapper<LedgerAccount> MAPPER = JdbcAccountStore::map;

    private static LedgerAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new LedgerAccount(
                rs.getObject("id", UUID.class),
                rs.getString("account_number"),
                AccountType.valueOf(rs.getString("account_type")),
                Currency.getInstance(rs.getString("currency").trim()),
                rs.getString("owner_ref"),
                rs.getString("name"),
                AccountStatus.valueOf(rs.getString("status")),
                rs.getBoolean("is_postable"),
                rs.getObject("parent_id", UUID.class),
                rs.getObject("opened_at", OffsetDateTime.class));
    }
}
