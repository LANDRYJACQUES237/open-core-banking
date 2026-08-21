package com.ocb.ledger.adapter.persistence;

import com.ocb.ledger.domain.Direction;
import com.ocb.ledger.domain.port.BalanceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcBalanceStore implements BalanceStore {

    private final JdbcClient jdbc;
    private final long snapshotLagSeconds;

    public JdbcBalanceStore(JdbcClient jdbc,
                            @Value("${ledger.snapshot.lag-seconds:30}") long snapshotLagSeconds) {
        this.jdbc = jdbc;
        this.snapshotLagSeconds = snapshotLagSeconds;
    }

    /**
     * Solde brut d'un compte : instantane, plus les ecritures posterieures.
     *
     * <p>C'est ce qui permet de tenir la promesse "aucun champ solde" sans que la lecture
     * degenere en parcours de tout l'historique. L'instantane n'est jamais une source de
     * verite : le retirer donne exactement le meme resultat, seulement plus lentement.
     */
    @Override
    public RawBalance rawBalanceOf(UUID accountId) {
        return jdbc.sql("""
                        WITH snap AS (SELECT up_to_entry_seq, raw_balance
                                        FROM ledger.account_balance_snapshot
                                       WHERE account_id = :id)
                        SELECT COALESCE((SELECT raw_balance FROM snap), 0)
                               + COALESCE((SELECT SUM(pl.signed_amount)
                                             FROM ledger.posting_line pl
                                             JOIN ledger.journal_entry je ON je.id = pl.journal_entry_id
                                            WHERE pl.account_id = :id
                                              AND je.entry_seq > COALESCE((SELECT up_to_entry_seq FROM snap), 0)),
                                          0) AS raw_balance,
                               GREATEST(
                                   COALESCE((SELECT up_to_entry_seq FROM snap), 0),
                                   COALESCE((SELECT MAX(je2.entry_seq)
                                               FROM ledger.posting_line pl2
                                               JOIN ledger.journal_entry je2 ON je2.id = pl2.journal_entry_id
                                              WHERE pl2.account_id = :id), 0)
                               ) AS entry_seq
                        """)
                .param("id", accountId)
                .query((rs, rowNum) -> new RawBalance(
                        rs.getBigDecimal("raw_balance"),
                        rs.getLong("entry_seq")))
                .single();
    }

    /**
     * Releve, solde progressif inclus.
     *
     * <p>Le solde progressif est calcule par fonction de fenetrage sur tout l'historique
     * du compte, puis la page est decoupee. C'est correct et simple, mais chaque page
     * relit l'historique complet : a l'echelle, il faudrait partir de l'instantane et
     * paginer par cle plutot que par decalage. Limite assumee de la Phase 1, signalee
     * ici plutot que decouverte plus tard.
     */
    @Override
    public List<StatementRow> statement(UUID accountId, int page, int size) {
        return jdbc.sql("""
                        WITH movements AS (
                            SELECT je.entry_ref, je.entry_seq, je.posted_at, je.value_date,
                                   je.description, je.transaction_ref,
                                   pl.line_no, pl.direction, pl.amount, pl.currency,
                                   SUM(pl.signed_amount) OVER (ORDER BY je.entry_seq, pl.line_no
                                       ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW) AS running_raw
                              FROM ledger.posting_line pl
                              JOIN ledger.journal_entry je ON je.id = pl.journal_entry_id
                             WHERE pl.account_id = :id
                        )
                        SELECT * FROM movements
                         ORDER BY entry_seq DESC, line_no DESC
                         OFFSET :offset LIMIT :size
                        """)
                .param("id", accountId)
                .param("offset", (long) page * size)
                .param("size", size)
                .query((rs, rowNum) -> new StatementRow(
                        rs.getString("entry_ref"),
                        rs.getLong("entry_seq"),
                        rs.getObject("posted_at", OffsetDateTime.class),
                        rs.getObject("value_date", LocalDate.class),
                        rs.getString("description"),
                        rs.getString("transaction_ref"),
                        Direction.valueOf(rs.getString("direction").trim()),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency").trim(),
                        rs.getBigDecimal("running_raw")))
                .list();
    }

    @Override
    public long statementSize(UUID accountId) {
        return jdbc.sql("SELECT COUNT(*) FROM ledger.posting_line WHERE account_id = :id")
                .param("id", accountId)
                .query(Long.class)
                .single();
    }

    /**
     * Rafraichissement incremental des instantanes.
     *
     * <p>Le point delicat est le choix du point d'arret. {@code entry_seq} est attribue a
     * l'insertion, pas au commit : une ecriture qui obtient le numero 5 peut valider apres
     * une ecriture numerotee 6. Prendre {@code MAX(entry_seq)} comme point d'arret risquerait
     * donc d'enjamber definitivement une ecriture encore en vol, qui ne serait jamais
     * integree a aucun instantane.
     *
     * <p>D'ou le retard applique : on ne consolide que les ecritures dont la date de
     * comptabilisation est anterieure a maintenant moins {@code ledger.snapshot.lag-seconds}.
     * Le raisonnement tient tant qu'aucune transaction d'ecriture ne dure plus longtemps que
     * ce retard, ce que garantit {@code idle_in_transaction_session_timeout}.
     *
     * <p>Et parce qu'un cache silencieusement faux serait pire que pas de cache,
     * {@link #verifySnapshots()} permet de transformer une eventuelle derive en anomalie
     * detectable plutot qu'en solde faux qui passe inapercu.
     */
    @Override
    public int refreshSnapshots() {
        return jdbc.sql("""
                        WITH watermark AS (
                            SELECT COALESCE(MAX(entry_seq), 0) AS seq
                              FROM ledger.journal_entry
                             WHERE posted_at < now() - make_interval(secs => :lag)
                        ),
                        delta AS (
                            SELECT pl.account_id, SUM(pl.signed_amount) AS delta
                              FROM ledger.posting_line pl
                              JOIN ledger.journal_entry je ON je.id = pl.journal_entry_id
                              LEFT JOIN ledger.account_balance_snapshot s ON s.account_id = pl.account_id
                             WHERE je.entry_seq > COALESCE(s.up_to_entry_seq, 0)
                               AND je.entry_seq <= (SELECT seq FROM watermark)
                             GROUP BY pl.account_id
                        )
                        INSERT INTO ledger.account_balance_snapshot
                            (account_id, up_to_entry_seq, raw_balance, computed_at)
                        SELECT d.account_id, (SELECT seq FROM watermark), d.delta, now()
                          FROM delta d
                        ON CONFLICT (account_id) DO UPDATE
                            SET raw_balance     = ledger.account_balance_snapshot.raw_balance
                                                  + EXCLUDED.raw_balance,
                                up_to_entry_seq = EXCLUDED.up_to_entry_seq,
                                computed_at     = EXCLUDED.computed_at
                        """)
                .param("lag", (double) snapshotLagSeconds)
                .update();
    }

    @Override
    public List<SnapshotDiscrepancy> verifySnapshots() {
        return jdbc.sql("""
                        SELECT s.account_id,
                               a.account_number,
                               COALESCE((SELECT SUM(pl.signed_amount)
                                           FROM ledger.posting_line pl
                                           JOIN ledger.journal_entry je ON je.id = pl.journal_entry_id
                                          WHERE pl.account_id = s.account_id
                                            AND je.entry_seq <= s.up_to_entry_seq), 0) AS recomputed,
                               s.raw_balance
                          FROM ledger.account_balance_snapshot s
                          JOIN ledger.account a ON a.id = s.account_id
                        """)
                .query((rs, rowNum) -> new SnapshotDiscrepancy(
                        rs.getObject("account_id", UUID.class),
                        rs.getString("account_number"),
                        rs.getBigDecimal("recomputed"),
                        rs.getBigDecimal("raw_balance")))
                .list()
                .stream()
                .filter(d -> d.recomputed().compareTo(d.snapshotted()) != 0)
                .toList();
    }

    @Override
    public void clearSnapshots() {
        jdbc.sql("DELETE FROM ledger.account_balance_snapshot").update();
    }

    @Override
    public BigDecimal globalImbalance() {
        return jdbc.sql("SELECT ledger.fn_global_imbalance()")
                .query(BigDecimal.class)
                .single();
    }
}
