package com.ocb.ledger;

import com.ocb.ledger.application.LedgerMaintenanceService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le controle d'integrite, rendu observable.
 *
 * <p>Le controle existait depuis la Phase 1 : la somme algebrique de toutes les ecritures
 * doit valoir zero, puisque chaque ecriture y contribue pour zero. Mais il ne faisait que
 * <b>journaliser</b> son resultat.
 *
 * <p><b>Un log n'est pas une alerte.</b> Personne ne lit les journaux d'un systeme qui va
 * bien ; on ne les ouvre qu'apres avoir su, par un autre chemin, qu'il ne va pas bien. Pour
 * un desequilibre comptable, cet autre chemin est le rapprochement bancaire — six semaines
 * plus tard, quand la correction coute infiniment plus cher.
 *
 * <p>Une jauge, elle, se surveille. C'est la difference entre « le systeme sait » et « on
 * sait ».
 */
class IntegrityMetricsIT extends LedgerIntegrationTestBase {

    @Autowired
    private LedgerMaintenanceService maintenance;

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("un grand livre equilibre publie un desequilibre nul")
    void abalancedLedgerReportsZero() {
        String wallet = openWallet("metriques-" + suffix);
        postEntry("integrite-" + suffix, "Encaissement de controle",
                "1100:DR:10000", wallet + ":CR:9900", "4100:CR:100");

        maintenance.checkIntegrity();

        assertThat(gauge("ocb.ledger.imbalance"))
                .as("chaque ecriture contribue pour zero a la somme algebrique")
                .isZero();
        assertThat(gauge("ocb.ledger.audit.chain.breaks")).isZero();
    }

    @Test
    @DisplayName("les jauges existent avant meme la premiere execution du controle")
    void gaugesExistBeforeTheFirstRun() {
        // Le point le moins evident, et celui qui compte le plus pour une alerte.
        //
        // Une jauge qui n'apparaitrait qu'apres la premiere execution du controle serait
        // absente pendant les premieres minutes de vie du service. Or une metrique absente
        // disparait des graphiques et ne declenche aucune alerte : elle ressemble a s'y
        // meprendre a un systeme sain. Mieux vaut un zero provisoirement faux qu'un silence
        // durablement trompeur.
        assertThat(meters.find("ocb.ledger.imbalance").gauge())
                .as("declaree au demarrage, pas a la premiere mesure")
                .isNotNull();
        assertThat(meters.find("ocb.ledger.snapshot.drift.count").gauge()).isNotNull();
        assertThat(meters.find("ocb.ledger.audit.chain.breaks").gauge()).isNotNull();
    }

    private double gauge(String name) {
        var gauge = meters.find(name).gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }
}
