package com.ocb.payment.domain.port;

/**
 * Serialisation des debits d'un portefeuille.
 *
 * <p><b>Le probleme.</b> Interdire le decouvert suppose de lire un solde puis d'ecrire en
 * fonction de ce qu'on a lu. Entre les deux, un second decaissement sur le meme
 * portefeuille peut lire le meme solde et se croire finançable lui aussi. Les deux
 * ecritures passent, et le portefeuille se retrouve a decouvert sans qu'aucune des deux
 * demandes n'ait enfreint la regle prise isolement.
 *
 * <p><b>Pourquoi pas un verrou en memoire.</b> Un {@code synchronized} ou un
 * {@link java.util.concurrent.locks.ReentrantLock} protege une instance. Des qu'il y en a
 * deux — ce que l'externalisation des groupes de consommation rend possible, et ce que
 * Kubernetes rendra normal — la garantie disparait sans le moindre signal : le code
 * continue de verrouiller, simplement plus rien n'est serialise entre les instances. Une
 * protection qui s'evapore en silence est pire qu'une absence de protection, parce qu'on
 * cesse d'y penser.
 *
 * <p>Le verrou vit donc dans la base, seul point que toutes les instances partagent.
 */
public interface WalletLock {

    /**
     * Prend le verrou du portefeuille jusqu'a la fin de la transaction courante.
     *
     * <p>Bloque tant qu'une autre transaction le detient. Il n'y a pas de version
     * non bloquante : echouer immediatement obligerait l'appelant a redemander, ce qui
     * transformerait une attente de quelques millisecondes en erreur visible pour le
     * client.
     *
     * <p>La liberation n'est pas exposee, et ce n'est pas un oubli : elle est faite par la
     * base a la validation comme a l'annulation. Un verrou qu'on relache a la main est un
     * verrou qu'on oublie de relacher sur un chemin d'erreur.
     *
     * @throws IllegalStateException si aucune transaction n'est active, auquel cas le
     *                               verrou serait relache immediatement apres avoir ete
     *                               pris — donc ne protegerait rien
     */
    void lockForUpdate(String walletAccountRef);
}
