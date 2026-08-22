package com.ocb.payment.domain;

public enum TransactionType {

    /** Encaissement : de l'argent entre depuis un portefeuille Mobile Money. */
    COLLECTION,

    /** Decaissement : de l'argent sort vers un portefeuille Mobile Money. */
    DISBURSEMENT,

    /**
     * Transfert entre deux portefeuilles internes.
     *
     * <p>Ne traverse aucune frontiere de service : c'est une seule ecriture equilibree
     * dans le grand livre, donc une transaction ACID. Aucune saga n'y est necessaire.
     */
    TRANSFER
}
