package com.ocb.platform.events;

/**
 * Noms de topics.
 *
 * <p>Convention : {@code ocb.<kind>.<domaine>.v<majeure>}, ou {@code kind} vaut
 * {@code cmd} pour une intention adressee a un service precis, et {@code evt} pour un
 * fait accompli dont l'emetteur ignore qui l'ecoute.
 *
 * <p>Un topic <b>par agregat</b>, pas par type d'evenement. Tous les evenements d'une
 * transaction partagent donc la meme cle de partition et restent ordonnes entre eux.
 * Decouper par type d'evenement ferait perdre cet ordre : rien ne garantirait qu'un
 * {@code completed} soit consomme apres le {@code requested} correspondant.
 */
public final class Topics {

    /** Commandes de payment-service vers provider-service. Cle : transactionId. */
    public static final String CMD_PROVIDER = "ocb.cmd.provider.v1";

    /** Issues d'operations operateur. Cle : transactionId. */
    public static final String EVT_PROVIDER = "ocb.evt.provider.v1";

    /** Evenements du cycle de vie d'une transaction de paiement. Cle : transactionId. */
    public static final String EVT_PAYMENT = "ocb.evt.payment.v1";

    /** Evenements comptables. Cle : accountId. */
    public static final String EVT_LEDGER = "ocb.evt.ledger.v1";

    private Topics() {
    }

    /**
     * Topic de rebut associe.
     *
     * <p>Un message qu'un consommateur ne saura jamais traiter — payload illisible,
     * type inconnu, invariant viole — doit sortir du topic principal. Le laisser
     * bloquerait la partition indefiniment : tous les messages suivants de la meme cle
     * attendraient derriere lui.
     */
    public static String deadLetter(String topic) {
        return topic + ".dlq";
    }
}
