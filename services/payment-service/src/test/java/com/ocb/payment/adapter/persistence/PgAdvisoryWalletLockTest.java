package com.ocb.payment.adapter.persistence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le garde-fou du verrou : refuser d'operer hors transaction.
 *
 * <p>C'est le mode de defaillance le plus pernicieux de {@code pg_advisory_xact_lock}.
 * Hors transaction, chaque instruction est sa propre transaction : le verrou est pris puis
 * relache aussitot. Aucune erreur, aucune trace — le code a l'air de verrouiller, et rien
 * n'est serialise. Le decouvert apparaitrait en production, sur un chemin d'appel qui
 * aurait perdu son {@code @Transactional} au detour d'un refactoring.
 *
 * <p>Le test ne demande aucune base : le controle a lieu avant la moindre requete, ce qui
 * est precisement ce qu'on veut verifier.
 */
class PgAdvisoryWalletLockTest {

    @Test
    @DisplayName("hors transaction, le verrou refuse plutot que de ne rien proteger")
    void refusesOutsideATransaction() {
        // Le JdbcClient est volontairement nul : si le garde-fou laissait passer, l'appel
        // echouerait par NullPointerException et non par le message attendu. Le test
        // distingue donc "refuse correctement" de "a essaye d'executer la requete".
        PgAdvisoryWalletLock lock = new PgAdvisoryWalletLock(null);

        assertThatThrownBy(() -> lock.lockForUpdate("2100.wallet-c"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hors transaction");
    }
}
