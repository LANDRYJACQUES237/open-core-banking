package com.ocb.ledger;

import com.ocb.ledger.domain.port.AuditStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Journal d'audit : insertion seule, et scellement par chainage de hachage.
 *
 * <p>L'interet du chainage n'est pas d'empecher la modification — les droits et les
 * triggers s'en chargent — mais de la rendre <b>detectable</b> si quelqu'un dispose
 * malgre tout des privileges pour la faire. Le test le verifie de la seule maniere
 * honnete : en desactivant temporairement la protection, en alterant une ligne, puis en
 * constatant que la verification le voit.
 */
class AuditTrailIT extends LedgerIntegrationTestBase {

    @Autowired
    private AuditStore audit;

    @Test
    @DisplayName("les operations sensibles laissent une trace")
    void sensitiveOperationsAreRecorded() throws SQLException {
        String wallet = openWallet("audited");
        ApiResponse entry = postEntry("audit-" + suffix, "encaissement",
                line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

        assertThat(countAudit("ACCOUNT_OPENED", wallet)).isEqualTo(1);
        assertThat(countAudit("ENTRY_POSTED", entry.entryRef())).isEqualTo(1);
    }

    @Test
    @DisplayName("un rejeu idempotent ne produit pas de seconde entree d'audit")
    void replayIsNotAudited() throws SQLException {
        String wallet = openWallet("auditreplay");
        String key = "auditreplay-" + suffix;

        ApiResponse first = postEntry(key, "encaissement",
                line("1100", "DR", "1000"), line(wallet, "CR", "1000"));
        postEntry(key, "encaissement", line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

        // Le rejeu n'a produit aucun effet : il n'a rien a raconter. Auditer un rejeu
        // gonflerait le journal d'evenements qui ne se sont pas produits.
        assertThat(countAudit("ENTRY_POSTED", first.entryRef())).isEqualTo(1);
    }

    @Test
    @DisplayName("la chaine de scellement est intacte apres scellement")
    void chainIsIntactAfterSealing() {
        String wallet = openWallet("chainok");
        postEntry("chainok-" + suffix, "encaissement",
                line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

        int sealed = audit.sealPending();
        assertThat(sealed).isPositive();
        assertThat(audit.verifyChain()).isEmpty();

        // Sceller a nouveau ne rescelle rien : l'operation est rejouable sans effet.
        assertThat(audit.sealPending()).isZero();
        assertThat(audit.verifyChain()).isEmpty();
    }

    @Test
    @DisplayName("alterer une entree scellee est detecte par la verification")
    void tamperingIsDetected() throws SQLException {
        String wallet = openWallet("tamper");
        postEntry("tamper-" + suffix, "encaissement",
                line("1100", "DR", "4200"), line(wallet, "CR", "4200"));

        audit.sealPending();
        assertThat(audit.verifyChain()).isEmpty();

        // Simulation d'un acteur disposant des droits du proprietaire du schema : il peut
        // desactiver le trigger, donc modifier une ligne. Ce qu'il ne peut pas faire sans
        // recalculer toute la chaine, c'est le faire sans laisser de trace.
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ledger.audit_log DISABLE TRIGGER trg_audit_log_immutable");
            int altered = statement.executeUpdate("""
                    UPDATE ledger.audit_log
                       SET action = 'RIEN_DU_TOUT'
                     WHERE resource_id = '%s' AND action = 'ACCOUNT_OPENED'
                    """.formatted(wallet));
            statement.execute("ALTER TABLE ledger.audit_log ENABLE TRIGGER trg_audit_log_immutable");
            assertThat(altered).as("la ligne visee a bien ete alteree").isEqualTo(1);
        }

        assertThat(audit.verifyChain())
                .as("la modification retroactive est detectee")
                .isNotEmpty()
                .anySatisfy(chainBreak ->
                        assertThat(chainBreak.reason()).contains("contenu modifie"));

        // Remise en etat, pour deux raisons.
        //
        // La premiere est pratique : la base est partagee par toute la suite de tests, et
        // une chaine laissee rompue ferait echouer le controle d'integrite global des
        // autres classes. C'est d'ailleurs ainsi que ce couplage s'est signale.
        //
        // La seconde est plus interessante : elle verifie que la detection n'est pas un
        // drapeau qui reste leve une fois declenche. Restaurer la valeur d'origine restaure
        // le hachage, donc la chaine redevient valide. Un detecteur qui resterait en alerte
        // apres correction serait inexploitable en supervision.
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE ledger.audit_log DISABLE TRIGGER trg_audit_log_immutable");
            statement.executeUpdate("""
                    UPDATE ledger.audit_log
                       SET action = 'ACCOUNT_OPENED'
                     WHERE resource_id = '%s' AND action = 'RIEN_DU_TOUT'
                    """.formatted(wallet));
            statement.execute("ALTER TABLE ledger.audit_log ENABLE TRIGGER trg_audit_log_immutable");
        }

        assertThat(audit.verifyChain())
                .as("la chaine redevient valide une fois la valeur d'origine retablie")
                .isEmpty();
    }

    @Test
    @DisplayName("le journal d'audit ne contient aucune donnee personnelle")
    void auditCarriesNoPersonalData() throws SQLException {
        String wallet = openWallet("privacy");
        postEntry("privacy-" + suffix, "encaissement",
                line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

        // Le grand livre ne detient aucune donnee personnelle ; son journal d'audit ne
        // doit pas en introduire par la bande. On verifie l'absence de toute forme de
        // MSISDN, la donnee personnelle qui circulera dans les autres services.
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("""
                    SELECT COUNT(*) FROM ledger.audit_log
                     WHERE COALESCE(payload::text, '') ~ '\\+?[0-9]{9,15}'
                        OR resource_id ~ '\\+[0-9]{9,15}'
                    """);
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    private int countAudit(String action, String resourceId) throws SQLException {
        try (Connection connection = ownerConnection(); Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery("""
                    SELECT COUNT(*) FROM ledger.audit_log
                     WHERE action = '%s' AND resource_id = '%s'
                    """.formatted(action, resourceId));
            rs.next();
            return rs.getInt(1);
        }
    }
}
