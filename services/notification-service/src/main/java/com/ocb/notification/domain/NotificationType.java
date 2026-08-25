package com.ocb.notification.domain;

/** Ce qui est arrive a l'argent, du point de vue de celui a qui on l'annonce. */
public enum NotificationType {

    COLLECTION_COMPLETED,
    COLLECTION_FAILED,
    DISBURSEMENT_COMPLETED,

    /**
     * Decaissement compense.
     *
     * <p>Distinct d'un echec, et le message doit l'etre aussi : le client a vu son
     * portefeuille debite, puis recredite. Lui dire seulement "echec" le laisserait
     * chercher son argent.
     */
    DISBURSEMENT_REVERSED,

    TRANSFER_COMPLETED,

    /**
     * Transaction en attente d'arbitrage humain.
     *
     * <p>Ne part jamais vers le client. Annoncer "nous ne savons pas ou est votre argent"
     * sans pouvoir donner de suite inquiete sans rien resoudre ; c'est l'exploitation qui
     * doit agir, et c'est donc elle qu'on previent.
     */
    MANUAL_REVIEW_REQUIRED
}
