package com.ocb.ledger.adapter.persistence;

import com.ocb.ledger.domain.Direction;
import com.ocb.ledger.domain.EntryLine;
import com.ocb.ledger.domain.JournalEntryDraft;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.LedgerErrors;
import com.ocb.ledger.domain.PostedEntry;
import com.ocb.ledger.domain.port.JournalStore;
import com.ocb.platform.domain.error.ConflictException;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcJournalStore implements JournalStore {

    private static final String SELECT_HEADER = """
            SELECT je.id, je.entry_ref, je.entry_seq, je.transaction_ref, je.description,
                   je.value_date, je.posted_at, je.request_fingerprint,
                   rev.entry_ref   AS reverses_entry_ref,
                   revby.entry_ref AS reversed_by_entry_ref
              FROM ledger.journal_entry je
              LEFT JOIN ledger.journal_entry rev   ON rev.id = je.reverses_entry_id
              LEFT JOIN ledger.journal_entry revby ON revby.reverses_entry_id = je.id
            """;

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public JdbcJournalStore(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Posted post(JournalEntryDraft draft,
                       Map<String, LedgerAccount> accounts,
                       String idempotencyKey,
                       String requestFingerprint,
                       UUID reversesEntryId,
                       String correlationId) {

        UUID entryId = UUID.randomUUID();

        try {
            // ON CONFLICT ... DO NOTHING plutot qu'un try/catch sur la violation d'unicite :
            // en PostgreSQL, une erreur avorte la transaction entiere, et il deviendrait
            // impossible de relire l'ecriture existante sans SAVEPOINT.
            //
            // Le comportement sous concurrence est ce qui rend l'idempotence reellement
            // sure : si une transaction concurrente a insere la meme cle sans avoir encore
            // valide, cet INSERT attend son issue plutot que d'echouer. Deux requetes
            // simultanees portant la meme cle ne peuvent donc pas produire deux ecritures.
            Optional<Header> header = jdbc.sql("""
                            INSERT INTO ledger.journal_entry
                                (id, entry_ref, idempotency_key, request_fingerprint, transaction_ref,
                                 description, value_date, source_service, correlation_id, reverses_entry_id)
                            VALUES
                                (:id, :ref, :key, :fingerprint, :txRef,
                                 :description, :valueDate, 'ledger-service', :correlationId, :reverses)
                            ON CONFLICT ON CONSTRAINT ux_journal_entry_idempotency_key DO NOTHING
                            RETURNING entry_seq, posted_at
                            """)
                    .param("id", entryId)
                    .param("ref", draft.entryRef())
                    .param("key", idempotencyKey)
                    .param("fingerprint", requestFingerprint)
                    .param("txRef", draft.transactionRef())
                    .param("description", draft.description())
                    .param("valueDate", draft.valueDate())
                    .param("correlationId", correlationId)
                    .param("reverses", reversesEntryId)
                    .query((rs, rowNum) -> new Header(
                            rs.getLong("entry_seq"),
                            rs.getObject("posted_at", OffsetDateTime.class)))
                    .optional();

            if (header.isEmpty()) {
                PostedEntry existing = findByIdempotencyKey(idempotencyKey).orElseThrow(() ->
                        new IllegalStateException(
                                "Conflit d'idempotence sans ecriture correspondante : " + idempotencyKey));
                return new Posted(existing, false);
            }

            for (EntryLine line : draft.lines()) {
                LedgerAccount account = accounts.get(line.accountNumber());
                if (account == null) {
                    throw new IllegalStateException(
                            "Compte non resolu par la couche application : " + line.accountNumber());
                }
                jdbc.sql("""
                                INSERT INTO ledger.posting_line
                                    (id, journal_entry_id, line_no, account_id, direction, amount, currency)
                                VALUES
                                    (:id, :entryId, :lineNo, :accountId, :direction, :amount, :currency)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("entryId", entryId)
                        .param("lineNo", line.lineNo())
                        .param("accountId", account.id())
                        .param("direction", line.direction().name())
                        .param("amount", line.amount().amount())
                        .param("currency", line.amount().currencyCode())
                        .update();
            }

            // Force l'evaluation de la contrainte differee maintenant, avant le COMMIT.
            //
            // Sans cela, le desequilibre ne serait detecte qu'au moment du commit, donc
            // apres le retour de la methode : l'exception remonterait depuis l'intercepteur
            // transactionnel, sous une forme difficile a traduire en reponse propre.
            // La contrainte reste differable — c'est ce qui autorise d'inserer l'en-tete
            // avant ses lignes — mais l'application choisit le moment ou elle veut le verdict.
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");

            PostedEntry posted = new PostedEntry(
                    entryId,
                    draft.entryRef(),
                    header.get().entrySeq(),
                    draft.transactionRef(),
                    draft.description(),
                    draft.valueDate(),
                    header.get().postedAt(),
                    reversesEntryId == null ? null : refOf(reversesEntryId),
                    null,
                    draft.lines(),
                    requestFingerprint);

            return new Posted(posted, true);

        } catch (DuplicateKeyException e) {
            throw translateDuplicate(e, draft.entryRef());
        } catch (DataIntegrityViolationException e) {
            throw translateIntegrity(e);
        }
    }

    @Override
    public Optional<PostedEntry> findByRef(String entryRef) {
        return findOne(SELECT_HEADER + " WHERE je.entry_ref = :v", entryRef);
    }

    @Override
    public Optional<PostedEntry> findByIdempotencyKey(String idempotencyKey) {
        return findOne(SELECT_HEADER + " WHERE je.idempotency_key = :v", idempotencyKey);
    }

    @Override
    public Optional<PostedEntry> findReversalOf(UUID reversedEntryId) {
        return findOne(SELECT_HEADER + " WHERE je.reverses_entry_id = :v", reversedEntryId);
    }

    @Override
    public boolean entryRefExists(String entryRef) {
        return Boolean.TRUE.equals(jdbc
                .sql("SELECT EXISTS (SELECT 1 FROM ledger.journal_entry WHERE entry_ref = :v)")
                .param("v", entryRef)
                .query(Boolean.class)
                .single());
    }

    // ---------------------------------------------------------------------------------

    private Optional<PostedEntry> findOne(String sql, Object value) {
        Optional<Row> row = jdbc.sql(sql).param("v", value)
                .query((rs, rowNum) -> new Row(
                        rs.getObject("id", UUID.class),
                        rs.getString("entry_ref"),
                        rs.getLong("entry_seq"),
                        rs.getString("transaction_ref"),
                        rs.getString("description"),
                        rs.getObject("value_date", java.time.LocalDate.class),
                        rs.getObject("posted_at", OffsetDateTime.class),
                        rs.getString("reverses_entry_ref"),
                        rs.getString("reversed_by_entry_ref"),
                        rs.getString("request_fingerprint")))
                .optional();

        return row.map(r -> new PostedEntry(
                r.id(), r.entryRef(), r.entrySeq(), r.transactionRef(), r.description(),
                r.valueDate(), r.postedAt(), r.reversesEntryRef(), r.reversedByEntryRef(),
                linesOf(r.id()), r.fingerprint()));
    }

    private List<EntryLine> linesOf(UUID entryId) {
        return jdbc.sql("""
                        SELECT pl.line_no, a.account_number, pl.direction, pl.amount, pl.currency
                          FROM ledger.posting_line pl
                          JOIN ledger.account a ON a.id = pl.account_id
                         WHERE pl.journal_entry_id = :id
                         ORDER BY pl.line_no
                        """)
                .param("id", entryId)
                .query((rs, rowNum) -> new EntryLine(
                        rs.getInt("line_no"),
                        rs.getString("account_number"),
                        Direction.valueOf(rs.getString("direction").trim()),
                        Money.of(rs.getBigDecimal("amount"), rs.getString("currency").trim())))
                .list();
    }

    private String refOf(UUID entryId) {
        return jdbc.sql("SELECT entry_ref FROM ledger.journal_entry WHERE id = :id")
                .param("id", entryId)
                .query(String.class)
                .single();
    }

    private RuntimeException translateDuplicate(DuplicateKeyException e, String entryRef) {
        String constraint = PostgresErrors.constraintName(e);
        if ("ux_journal_entry_ref".equals(constraint)) {
            return new ConflictException(
                    LedgerErrors.ENTRY_REF_TAKEN,
                    "La reference d'ecriture %s est deja utilisee".formatted(entryRef));
        }
        if ("ux_journal_entry_reverses".equals(constraint)) {
            // Course entre deux contre-passations de la meme ecriture. L'unicite en base
            // garantit qu'une seule aboutit : c'est ce qui rend la compensation d'une
            // saga sure, meme rejouee en parallele.
            return new ConflictException(
                    LedgerErrors.ALREADY_REVERSED,
                    "Cette ecriture vient d'etre contre-passee par une operation concurrente");
        }
        return e;
    }

    private RuntimeException translateIntegrity(DataIntegrityViolationException e) {
        if (!PostgresErrors.isDeferredBalanceViolation(e)) {
            return e;
        }
        String raw = PostgresErrors.rawMessage(e);
        String code = raw.startsWith("LEDGER_MIXED_CURRENCY")
                ? LedgerErrors.MIXED_CURRENCY
                : LedgerErrors.UNBALANCED_ENTRY;
        // Ce chemin ne devrait jamais s'ouvrir : le domaine a deja valide l'equilibre.
        // S'il s'ouvre, c'est que le domaine et la base divergent, ce qui est une
        // information precieuse — d'ou le message explicite plutot qu'un 500 muet.
        return new InvariantViolationException(code,
                "Ecriture refusee par la contrainte d'equilibre de la base : " + raw);
    }

    private record Header(long entrySeq, OffsetDateTime postedAt) {
    }

    private record Row(UUID id, String entryRef, long entrySeq, String transactionRef,
                       String description, java.time.LocalDate valueDate, OffsetDateTime postedAt,
                       String reversesEntryRef, String reversedByEntryRef, String fingerprint) {
    }
}
