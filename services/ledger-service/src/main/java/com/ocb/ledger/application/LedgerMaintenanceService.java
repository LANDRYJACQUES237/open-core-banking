package com.ocb.ledger.application;

import com.ocb.ledger.domain.port.AuditStore;
import io.micrometer.core.instrument.MeterRegistry;
import com.ocb.ledger.domain.port.BalanceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Taches d'entretien du grand livre : consolidation des instantanes, scellement du
 * journal d'audit, controles de coherence.
 *
 * <p>Aucune de ces taches ne participe au chemin d'ecriture. Elles sont toutes
 * rejouables sans effet de bord : les relancer deux fois donne le meme resultat.
 */
@Service
public class LedgerMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(LedgerMaintenanceService.class);

    private final BalanceStore balances;
    private final AuditStore audit;

    /**
     * Dernier rapport d'integrite, publie en jauges.
     *
     * <p>Le controle coute cher — il parcourt tout le grand livre — et ne peut donc pas
     * etre execute a chaque interrogation de Prometheus. Les jauges lisent le dernier
     * resultat connu ; leur fraicheur est celle de {@code ledger.integrity.interval}.
     */
    private final AtomicReference<IntegrityReport> lastReport =
            new AtomicReference<>(IntegrityReport.notYetRun());

    public LedgerMaintenanceService(BalanceStore balances, AuditStore audit,
                                    MeterRegistry meters) {
        this.balances = balances;
        this.audit = audit;

        // Le controle d'integrite existait deja, mais il ne faisait que journaliser. Un
        // log n'est pas une alerte : personne ne le lit avant qu'un rapprochement
        // comptable n'ait deja revele le probleme, des semaines plus tard.
        //
        // La valeur absolue du desequilibre est publiee plutot que sa valeur signee : ce
        // qui doit declencher une alerte est l'ecart a zero, quel que soit son sens.
        meters.gauge("ocb.ledger.imbalance", this,
                self -> self.lastReport.get().globalImbalance().abs().doubleValue());

        meters.gauge("ocb.ledger.snapshot.drift.count", this,
                self -> self.lastReport.get().snapshotDrift().size());

        meters.gauge("ocb.ledger.audit.chain.breaks", this,
                self -> self.lastReport.get().auditChainBreaks().size());
    }

    @Scheduled(fixedDelayString = "${ledger.snapshot.interval:PT60S}")
    @Transactional
    public int refreshBalanceSnapshots() {
        int refreshed = balances.refreshSnapshots();
        if (refreshed > 0) {
            log.debug("Instantanes de solde rafraichis : {} compte(s)", refreshed);
        }
        return refreshed;
    }

    @Scheduled(fixedDelayString = "${ledger.audit.seal-interval:PT30S}")
    @Transactional
    public int sealAuditTrail() {
        int sealed = audit.sealPending();
        if (sealed > 0) {
            log.debug("Journal d'audit scelle : {} entree(s)", sealed);
        }
        return sealed;
    }

    /**
     * Controle de coherence globale.
     *
     * <p>La somme algebrique de toutes les ecritures du grand livre doit valoir zero,
     * puisque chaque ecriture y contribue pour zero. Toute autre valeur signale une
     * corruption, et il vaut infiniment mieux la decouvrir par une alerte que par un
     * rapprochement bancaire six semaines plus tard.
     */
    @Scheduled(fixedDelayString = "${ledger.integrity.interval:PT300S}")
    @Transactional(readOnly = true)
    public IntegrityReport checkIntegrity() {
        BigDecimal imbalance = balances.globalImbalance();
        List<BalanceStore.SnapshotDiscrepancy> drift = balances.verifySnapshots();
        List<AuditStore.ChainBreak> chainBreaks = audit.verifyChain();

        IntegrityReport report = new IntegrityReport(imbalance, drift, chainBreaks);
        lastReport.set(report);
        if (!report.healthy()) {
            log.error("Controle d'integrite en echec : desequilibre={}, instantanes derives={}, "
                            + "ruptures de chaine d'audit={}",
                    imbalance, drift.size(), chainBreaks.size());
        }
        return report;
    }

    public record IntegrityReport(BigDecimal globalImbalance,
                                  List<BalanceStore.SnapshotDiscrepancy> snapshotDrift,
                                  List<AuditStore.ChainBreak> auditChainBreaks) {

        /**
         * Etat avant la premiere execution du controle.
         *
         * <p>Zero plutot qu'une absence de valeur : une jauge manquante disparait des
         * graphiques et des alertes, ce qui ressemble a s'y meprendre a un systeme sain.
         * Un zero au demarrage est faux pendant quelques minutes ; une jauge absente est
         * trompeuse indefiniment.
         */
        static IntegrityReport notYetRun() {
            return new IntegrityReport(BigDecimal.ZERO, List.of(), List.of());
        }

        public boolean healthy() {
            return globalImbalance.compareTo(BigDecimal.ZERO) == 0
                    && snapshotDrift.isEmpty()
                    && auditChainBreaks.isEmpty();
        }
    }
}
