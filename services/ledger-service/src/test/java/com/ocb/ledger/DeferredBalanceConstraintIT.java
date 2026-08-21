package com.ocb.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La contrainte d'equilibre, verifiee en contournant entierement l'application.
 *
 * <p>C'est le point de ce test : le domaine valide deja l'equilibre, donc passer par
 * l'API ne prouverait que le domaine. Ici on ecrit en SQL brut, comme le ferait un script
 * de reprise, une migration de donnees ou un correctif passe en console — c'est-a-dire
 * les chemins par lesquels un grand livre se desequilibre reellement.
 *
 * <p>Les comptes du plan de comptes ont des identifiants deterministes, ce qui permet de
 * les referencer directement sans les relire.
 */
class DeferredBalanceConstraintIT extends LedgerIntegrationTestBase {

    private static final UUID FLOAT_MTN = UUID.fromString("00000000-0000-4000-8000-000000001100");
    private static final UUID FEE_INCOME = UUID.fromString("00000000-0000-4000-8000-000000004100");
    private static final UUID SETTLEMENT = UUID.fromString("00000000-0000-4000-8000-000000001200");

    @Test
    @DisplayName("une ecriture desequilibree est refusee au COMMIT, sans aucune aide de l'application")
    void unbalancedEntryIsRejectedAtCommit() throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-unbalanced-" + suffix);
            insertLine(connection, entryId, 1, FLOAT_MTN, "DR", "10000");
            insertLine(connection, entryId, 2, FEE_INCOME, "CR", "9999");

            // Les INSERT passent : la contrainte est differee, elle n'a pas encore parle.
            // C'est precisement ce qui permet d'inserer l'en-tete avant ses lignes.
            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("LEDGER_UNBALANCED")
                    .hasMessageContaining("ecart debit/credit");
        }
    }

    @Test
    @DisplayName("un ecart d'une unite suffit")
    void offByOneIsRejected() throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-offbyone-" + suffix);
            insertLine(connection, entryId, 1, FLOAT_MTN, "DR", "2");
            insertLine(connection, entryId, 2, FEE_INCOME, "CR", "1");
            assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("une ecriture sans ligne est refusee : le controle ne porte pas que sur les lignes")
    void headerWithoutLinesIsRejected() throws SQLException {
        // Une contrainte posee uniquement sur posting_line ne se declencherait jamais ici,
        // puisqu'aucune ligne n'est inseree. L'en-tete orpheline passerait, et le grand
        // livre contiendrait une ecriture qui ne deplace rien.
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            insertHeader(connection, "raw-empty-" + suffix);
            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("au moins 2 sont requises");
        }
    }

    @Test
    @DisplayName("une ecriture a une seule ligne est refusee")
    void singleLineIsRejected() throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-single-" + suffix);
            insertLine(connection, entryId, 1, FLOAT_MTN, "DR", "100");
            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("au moins 2 sont requises");
        }
    }

    @Test
    @DisplayName("une ecriture multidevise est refusee, meme si elle s'annule arithmetiquement")
    void mixedCurrencyIsRejected() throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-mixed-" + suffix);
            insertLine(connection, entryId, 1, FLOAT_MTN, "DR", "100", "XAF");
            insertLine(connection, entryId, 2, SETTLEMENT, "CR", "100", "EUR");
            assertThatThrownBy(connection::commit)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("LEDGER_MIXED_CURRENCY");
        }
    }

    @Test
    @DisplayName("un montant negatif est refuse immediatement, sans attendre le COMMIT")
    void negativeAmountIsRejectedImmediately() throws SQLException {
        // Contrainte CHECK ordinaire, non differee : elle se declenche a l'INSERT.
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-negative-" + suffix);
            assertThatThrownBy(() -> insertLine(connection, entryId, 1, FLOAT_MTN, "CR", "-100"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ck_posting_line_amount_positive");
            connection.rollback();
        }
    }

    @Test
    @DisplayName("une ecriture equilibree passe, et le grand livre reste globalement a zero")
    void balancedEntryIsAccepted() throws SQLException {
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-balanced-" + suffix);
            insertLine(connection, entryId, 1, FLOAT_MTN, "DR", "7500");
            insertLine(connection, entryId, 2, FEE_INCOME, "CR", "7500");
            assertThatCode(connection::commit).doesNotThrowAnyException();
        }

        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("SELECT ledger.fn_global_imbalance()");
            assertThat(rs.next()).isTrue();
            // La somme de toutes les ecritures du grand livre doit valoir zero, puisque
            // chaque ecriture y contribue pour zero. Toute autre valeur signale une corruption.
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("SET CONSTRAINTS ALL IMMEDIATE avance le verdict avant le COMMIT")
    void immediateEvaluationCanBeForced() throws SQLException {
        // C'est ce que fait l'application : elle garde une contrainte differable, pour
        // pouvoir inserer l'en-tete avant les lignes, mais choisit le moment du verdict
        // afin de le traduire en reponse HTTP propre plutot qu'en echec de commit.
        try (Connection connection = ownerConnection()) {
            connection.setAutoCommit(false);
            UUID entryId = insertHeader(connection, "raw-immediate-" + suffix);
            insertLine(connection, entryId, 1, FLOAT_MTN, "DR", "500");
            insertLine(connection, entryId, 2, FEE_INCOME, "CR", "400");

            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("SET CONSTRAINTS ALL IMMEDIATE"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("LEDGER_UNBALANCED");
            }
            connection.rollback();
        }
    }

    // ---------------------------------------------------------------------------------

    private UUID insertHeader(Connection connection, String reference) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO ledger.journal_entry
                    (id, entry_ref, idempotency_key, request_fingerprint, description,
                     value_date, source_service)
                VALUES (?, ?, ?, 'raw', 'ecriture inseree en SQL brut', CURRENT_DATE, 'test')
                """)) {
            ps.setObject(1, id);
            ps.setString(2, reference);
            ps.setString(3, reference);
            ps.executeUpdate();
        }
        return id;
    }

    private void insertLine(Connection connection, UUID entryId, int lineNo, UUID accountId,
                            String direction, String amount) throws SQLException {
        insertLine(connection, entryId, lineNo, accountId, direction, amount, "XAF");
    }

    private void insertLine(Connection connection, UUID entryId, int lineNo, UUID accountId,
                            String direction, String amount, String currency) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO ledger.posting_line
                    (id, journal_entry_id, line_no, account_id, direction, amount, currency)
                VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), ?)
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, entryId);
            ps.setInt(3, lineNo);
            ps.setObject(4, accountId);
            ps.setString(5, direction);
            ps.setString(6, amount);
            ps.setString(7, currency);
            ps.executeUpdate();
        }
    }
}
