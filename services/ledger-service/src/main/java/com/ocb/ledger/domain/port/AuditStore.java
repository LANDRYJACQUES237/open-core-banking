package com.ocb.ledger.domain.port;

import com.ocb.ledger.domain.AuditEvent;

import java.util.List;

/** Port du journal d'audit. Insertion seule ; le scellement se fait dans une table separee. */
public interface AuditStore {

    void append(AuditEvent event);

    /**
     * Calcule le chainage de hachage des entrees non encore scellees.
     *
     * <p>Le scellement est asynchrone et non pas fait a l'insertion, pour une raison de
     * fond : chainer synchroniquement obligerait chaque ecriture a lire le hachage de la
     * precedente, donc a serialiser toutes les ecritures du service derriere un verrou
     * unique. Un grand livre qui ne peut traiter qu'une operation a la fois n'est pas
     * utilisable. Le scellement differe preserve la propriete recherchee — toute
     * suppression ou modification retroactive devient detectable — sans ce cout.
     *
     * @return le nombre d'entrees scellees
     */
    int sealPending();

    /**
     * Reverifie le chainage des entrees scellees.
     *
     * @return les ruptures constatees, vide si la chaine est intacte
     */
    List<ChainBreak> verifyChain();

    record ChainBreak(long seq, String reason) {
    }
}
