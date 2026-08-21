package com.ocb.ledger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'immuabilite du grand livre, verifiee sur ses deux couches de protection.
 *
 * <p>Les tester separement n'est pas du zele : elles ne protegent pas des memes
 * situations. Les droits arretent l'application et toute injection SQL qui s'executerait
 * sous son role, mais pas un administrateur. Les triggers arretent tout le monde,
 * y compris un superutilisateur en console un dimanche soir.
 */
class ImmutabilityIT extends LedgerIntegrationTestBase {

    private String entryRef;
    private String wallet;

    @BeforeEach
    void postAnEntry() {
        wallet = openWallet("immutable");
        LedgerIntegrationTestBase.ApiResponse response = postEntry(
                "immutability-" + suffix, "ecriture temoin",
                line("1100", "DR", "1000"),
                line(wallet, "CR", "1000"));
        assertThat(response.status()).isEqualTo(201);
        entryRef = response.entryRef();
    }

    @Nested
    @DisplayName("Protection par trigger : s'applique a tous les roles")
    class ByTrigger {

        @Test
        @DisplayName("modifier une ecriture est refuse, meme pour le proprietaire du schema")
        void updateOnJournalEntry() throws SQLException {
            try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE ledger.journal_entry SET description = 'altere' WHERE entry_ref = '%s'"
                                .formatted(entryRef)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("LEDGER_IMMUTABLE")
                        .hasMessageContaining("contre-passation");
            }
        }

        @Test
        @DisplayName("supprimer une ecriture est refuse")
        void deleteOnJournalEntry() throws SQLException {
            try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "DELETE FROM ledger.journal_entry WHERE entry_ref = '%s'".formatted(entryRef)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("LEDGER_IMMUTABLE");
            }
        }

        @Test
        @DisplayName("modifier le montant d'une ligne est refuse")
        void updateOnPostingLine() throws SQLException {
            try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE ledger.posting_line SET amount = amount * 2"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("LEDGER_IMMUTABLE");
            }
        }

        @Test
        @DisplayName("TRUNCATE est refuse : sans trigger d'instruction, une seule commande suffirait")
        void truncate() throws SQLException {
            // TRUNCATE ne declenche pas les triggers ligne a ligne. Une protection
            // limitee a BEFORE UPDATE OR DELETE laisserait donc un moyen d'effacer le
            // grand livre entier en une commande.
            try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("TRUNCATE ledger.posting_line CASCADE"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("LEDGER_IMMUTABLE");
            }
        }

        @Test
        @DisplayName("le journal d'audit est protege de la meme maniere")
        void auditLog() throws SQLException {
            try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE ledger.audit_log SET action = 'RIEN'"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("LEDGER_IMMUTABLE");
            }
        }
    }

    @Nested
    @DisplayName("Protection par droits : le role applicatif n'a simplement pas le pouvoir")
    class ByPrivilege {

        @Test
        @DisplayName("le role d'execution ne peut pas modifier une ecriture")
        void updateDenied() throws SQLException {
            try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate(
                        "UPDATE ledger.journal_entry SET description = 'altere' WHERE entry_ref = '%s'"
                                .formatted(entryRef)))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }

        @Test
        @DisplayName("le role d'execution ne peut pas supprimer une ecriture")
        void deleteDenied() throws SQLException {
            try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM ledger.posting_line"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("permission denied");
            }
        }

        @Test
        @DisplayName("le role d'execution lit et insere sans probleme : l'application fonctionne bien sous ces droits")
        void readAndInsertAllowed() throws SQLException {
            // Contre-preuve indispensable. Sans elle, les deux tests precedents pourraient
            // passer simplement parce que le role n'a aucun droit du tout, et l'application
            // ne fonctionnerait pas en production.
            try (Connection connection = appConnection(); Statement statement = connection.createStatement()) {
                var rs = statement.executeQuery(
                        "SELECT COUNT(*) FROM ledger.journal_entry WHERE entry_ref = '%s'".formatted(entryRef));
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            // Et l'API, qui tourne sous ce meme role, vient d'ecrire une ecriture en @BeforeEach.
            assertThat(balanceOf(wallet)).isEqualByComparingTo("1000");
        }
    }
}
