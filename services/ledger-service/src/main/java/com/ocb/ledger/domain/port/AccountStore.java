package com.ocb.ledger.domain.port;

import com.ocb.ledger.domain.LedgerAccount;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Port de persistance du plan de comptes.
 *
 * <p>Le domaine declare ce dont il a besoin ; l'adaptateur decide comment le fournir.
 * Cette interface est ce qui permet a {@code com.ocb.ledger.domain} de ne dependre ni de
 * Spring, ni de JDBC — propriete verifiee par un test ArchUnit, pas seulement souhaitee.
 */
public interface AccountStore {

    Optional<LedgerAccount> findByNumber(String accountNumber);

    Optional<LedgerAccount> findByIdempotencyKey(String idempotencyKey);

    /**
     * Charge plusieurs comptes en une seule lecture, indexes par numero.
     *
     * <p>Une ecriture designe entre 2 et 100 comptes : les lire un par un produirait
     * autant d'allers-retours vers la base pour une operation qui doit rester breve.
     * Les numeros introuvables sont simplement absents du resultat, a charge de
     * l'appelant de decider quoi en faire.
     */
    Map<String, LedgerAccount> findByNumbers(Collection<String> accountNumbers);

    /**
     * Ouvre un compte.
     *
     * @return le compte ouvert, et {@code true} s'il vient d'etre cree, {@code false}
     * si la cle d'idempotence designait un compte deja ouvert
     */
    Opened open(LedgerAccount account, String idempotencyKey);

    record Opened(LedgerAccount account, boolean created) {
    }
}
