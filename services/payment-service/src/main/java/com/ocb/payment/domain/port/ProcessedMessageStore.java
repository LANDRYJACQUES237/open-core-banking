package com.ocb.payment.domain.port;

/**
 * Deduplication des messages consommes.
 *
 * <p>Kafka garantit une livraison <b>au moins une fois</b>. Un consommateur qui redemarre
 * entre le traitement d'un message et la validation de son offset le recevra a nouveau.
 * Sans cette table, chaque redemarrage rejouerait des effets metier.
 */
public interface ProcessedMessageStore {

    /**
     * Enregistre le message comme traite.
     *
     * <p>Doit etre appele <b>dans la meme transaction que l'effet metier, et avant lui</b>.
     * L'ordre n'est pas arbitraire : inserer apres l'effet rouvrirait exactement la fenetre
     * que ce mecanisme ferme — un arret entre les deux laisserait l'effet applique sans
     * trace, donc rejouable.
     *
     * @return {@code true} si le message n'avait jamais ete traite, {@code false} s'il
     * s'agit d'un doublon qu'il faut acquitter sans rien faire
     */
    boolean markProcessed(String consumerGroup, String eventId, String eventType);
}
