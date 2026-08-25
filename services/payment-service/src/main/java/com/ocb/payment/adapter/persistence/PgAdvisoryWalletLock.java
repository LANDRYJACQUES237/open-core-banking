package com.ocb.payment.adapter.persistence;

import com.ocb.payment.domain.port.WalletLock;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Verrou consultatif PostgreSQL, portee transaction.
 *
 * <p><b>Pourquoi un verrou consultatif plutot qu'un {@code SELECT ... FOR UPDATE}.</b>
 * Verrouiller une ligne suppose qu'elle existe. Il faudrait donc tenir une table de
 * portefeuilles dans cette base — une duplication du grand livre, avec la question de qui
 * y insere la ligne la premiere fois et la course que cette insertion rouvrirait. Le
 * verrou consultatif ne verrouille rien de reel : il verrouille un nombre, ce qui est
 * exactement ce dont on a besoin ici.
 *
 * <p><b>Portee transaction.</b> {@code pg_advisory_xact_lock} et non
 * {@code pg_advisory_lock} : la base relache a la validation comme a l'annulation. La
 * variante de session survivrait a un rollback et resterait detenue par une connexion
 * rendue au pool, ou elle bloquerait un appelant sans rapport — un interblocage
 * particulierement penible a diagnostiquer.
 */
@Repository
public class PgAdvisoryWalletLock implements WalletLock {

    /**
     * Espace de noms des verrous de portefeuille.
     *
     * <p>Les verrous consultatifs partagent un espace unique a l'echelle de la base. Une
     * graine distincte par usage evite qu'un futur verrou sur un autre objet tombe par
     * hasard sur le meme nombre et serialise deux choses sans rapport.
     */
    private static final long WALLET_NAMESPACE = 0x0CB_0001L;

    private final JdbcClient jdbc;

    public PgAdvisoryWalletLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockForUpdate(String walletAccountRef) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Sans transaction, chaque instruction est sa propre transaction : le verrou
            // serait relache a la ligne suivante. Le code aurait l'air de verrouiller,
            // les tests de concurrence passeraient parfois, et le decouvert apparaitrait
            // en production. Mieux vaut refuser bruyamment.
            throw new IllegalStateException(
                    "Verrou de portefeuille demande hors transaction : il serait relache "
                            + "immediatement et ne protegerait rien");
        }

        // hashtextextended plutot que hashtext : 64 bits au lieu de 32. Une collision ne
        // serait pas incorrecte — deux portefeuilles sans rapport seraient serialises,
        // donc simplement ralentis — mais autant la rendre negligeable.
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:wallet, :seed))")
                .param("wallet", walletAccountRef)
                .param("seed", WALLET_NAMESPACE)
                // pg_advisory_xact_lock rend le type void : on materialise la ligne sans
                // chercher a la convertir, l'effet recherche etant l'acquisition elle-meme.
                .query()
                .listOfRows();
    }
}
