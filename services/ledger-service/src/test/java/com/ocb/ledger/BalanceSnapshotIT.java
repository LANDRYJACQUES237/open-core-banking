package com.ocb.ledger;

import com.ocb.ledger.application.LedgerMaintenanceService;
import com.ocb.ledger.domain.port.BalanceStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les instantanes de solde sont un cache, jamais une source de verite.
 *
 * <p>La promesse "aucun champ solde n'est jamais ecrit" serait vide si un instantane
 * pouvait faire diverger le resultat. La propriete qui rend cette table legitime est
 * simple a enoncer et a verifier : on doit pouvoir la vider entierement et retrouver
 * exactement les memes soldes.
 */
@TestPropertySource(properties = "ledger.snapshot.lag-seconds=0")
class BalanceSnapshotIT extends LedgerIntegrationTestBase {

    @Autowired
    private LedgerMaintenanceService maintenance;

    @Autowired
    private BalanceStore balances;

    @Test
    @DisplayName("vider les instantanes ne change aucun solde")
    void snapshotsAreRebuildable() {
        Map<String, BigDecimal> wallets = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            String wallet = openWallet("snap" + i);
            for (int movement = 1; movement <= 4; movement++) {
                postEntry("snap-%s-%d-%d".formatted(suffix, i, movement), "mouvement",
                        line("1100", "DR", String.valueOf(movement * 1000)),
                        line(wallet, "CR", String.valueOf(movement * 1000)));
            }
            wallets.put(wallet, balanceOf(wallet));
        }

        // Etat de reference, calcule sans aucun instantane.
        assertThat(wallets.values()).allSatisfy(b -> assertThat(b).isEqualByComparingTo("10000"));

        int refreshed = maintenance.refreshBalanceSnapshots();
        assertThat(refreshed).as("des instantanes ont bien ete ecrits").isPositive();

        wallets.forEach((wallet, expected) ->
                assertThat(balanceOf(wallet))
                        .as("solde de %s apres consolidation", wallet)
                        .isEqualByComparingTo(expected));

        // Des ecritures posterieures a l'instantane doivent continuer a s'y ajouter.
        String first = wallets.keySet().iterator().next();
        postEntry("snap-after-" + suffix, "apres instantane",
                line("1100", "DR", "500"), line(first, "CR", "500"));
        assertThat(balanceOf(first)).isEqualByComparingTo("10500");

        balances.clearSnapshots();

        wallets.forEach((wallet, expected) -> {
            BigDecimal afterClearing = wallet.equals(first) ? expected.add(new BigDecimal("500")) : expected;
            assertThat(balanceOf(wallet))
                    .as("solde de %s apres suppression complete des instantanes", wallet)
                    .isEqualByComparingTo(afterClearing);
        });
    }

    @Test
    @DisplayName("consolider deux fois de suite ne double aucun solde")
    void refreshIsIdempotent() {
        // Le rafraichissement est incremental : il ajoute un delta au solde deja
        // consolide. Une erreur de borne le ferait cumuler deux fois les memes ecritures,
        // et le solde doublerait sans que rien ne le signale.
        String wallet = openWallet("idempotent");
        postEntry("snapidem-" + suffix, "credit",
                line("1100", "DR", "7777"), line(wallet, "CR", "7777"));

        maintenance.refreshBalanceSnapshots();
        maintenance.refreshBalanceSnapshots();
        maintenance.refreshBalanceSnapshots();

        assertThat(balanceOf(wallet)).isEqualByComparingTo("7777");
        assertThat(balances.verifySnapshots())
                .as("aucun instantane ne diverge de son recalcul")
                .isEmpty();
    }

    @Test
    @DisplayName("le controle d'integrite globale passe : la somme du grand livre vaut zero")
    void ledgerStaysGloballyBalanced() {
        String wallet = openWallet("integrity");
        postEntry("integrity-" + suffix, "encaissement",
                line("1100", "DR", "9850"),
                line("5100", "DR", "150"),
                line(wallet, "CR", "9900"),
                line("4100", "CR", "100"));

        maintenance.refreshBalanceSnapshots();
        maintenance.sealAuditTrail();

        LedgerMaintenanceService.IntegrityReport report = maintenance.checkIntegrity();

        assertThat(report.globalImbalance()).isEqualByComparingTo("0");
        assertThat(report.snapshotDrift()).isEmpty();
        assertThat(report.auditChainBreaks()).isEmpty();
        assertThat(report.healthy()).isTrue();
    }
}
