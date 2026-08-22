package com.ocb.payment.domain;

/**
 * Etats d'une transaction de paiement.
 *
 * <p>Deux distinctions structurent cette enumeration, et les confondre serait une faute
 * de conception, pas un detail de nommage.
 *
 * <p><b>Terminal ne veut pas dire "fini".</b> {@link #COMPLETED}, {@link #FAILED} et
 * {@link #REVERSED} sont definitifs : plus aucune transition n'en sort. C'est ce qui
 * neutralise un callback tardif — l'operateur peut reessayer autant qu'il veut, l'etat ne
 * bougera plus.
 *
 * <p><b>{@link #MANUAL_REVIEW} n'est pas un echec.</b> Il signale qu'aucune conclusion n'a
 * pu etre tiree, typiquement parce que l'operateur n'a jamais repondu. L'argent a
 * peut-etre bouge. Le ranger avec {@link #FAILED} serait la faute la plus couteuse d'un
 * systeme de paiement : elle transformerait une incertitude en certitude fausse, et
 * declencherait un remboursement pour un paiement qui a peut-etre reussi.
 */
public enum TransactionStatus {

    /** Demande enregistree, rien n'a encore ete demande a l'operateur. */
    CREATED,

    /** Commande emise vers l'operateur. On attend qu'il accuse reception. */
    PENDING_PROVIDER,

    /** L'operateur a accepte la demande. Le resultat reste inconnu. */
    PROVIDER_ACCEPTED,

    /** L'operateur a confirme. L'argent a bouge chez lui, il reste a l'enregistrer. */
    PROVIDER_CONFIRMED,

    /** L'operateur a refuse, de maniere definitive et explicite. */
    PROVIDER_DECLINED,

    /** Ecriture comptable en cours. */
    POSTING,

    /** Compensation en cours : une ecriture doit etre contre-passee. */
    COMPENSATING,

    /**
     * Aucune conclusion possible automatiquement. La reconciliation ou un humain tranche.
     * Ce n'est pas un echec.
     */
    MANUAL_REVIEW,

    /** Terminal. L'operation a abouti et est comptabilisee. */
    COMPLETED,

    /** Terminal. L'operation a echoue, rien n'a bouge. */
    FAILED,

    /** Terminal. L'operation a ete compensee par contre-passation. */
    REVERSED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REVERSED;
    }
}
