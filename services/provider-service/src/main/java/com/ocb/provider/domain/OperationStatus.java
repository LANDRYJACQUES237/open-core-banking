package com.ocb.provider.domain;

/**
 * Etat du dialogue avec un operateur.
 *
 * <p>La distinction qui gouverne tout le service est entre {@link #FAILED} et
 * {@link #UNRESOLVED}.
 *
 * <p>{@link #FAILED} signifie que l'operateur a <b>dit non</b>. C'est une reponse : on
 * sait que rien n'a bouge.
 *
 * <p>{@link #UNRESOLVED} signifie qu'on a cesse de demander sans avoir jamais obtenu de
 * reponse. Ce n'est pas un echec, c'est une ignorance. L'argent a peut-etre bouge chez
 * l'operateur. Les ranger ensemble transformerait une incertitude en certitude fausse, et
 * declencherait un remboursement pour un paiement qui a peut-etre abouti.
 */
public enum OperationStatus {

    /**
     * Demande emise, aucun accuse de reception.
     *
     * <p>Egalement l'etat d'une demande dont l'appel a expire : on ne sait meme pas si
     * elle est parvenue a l'operateur.
     */
    PENDING,

    /** L'operateur a accuse reception. Le resultat reste inconnu. */
    ACCEPTED,

    /** Statut definitif favorable. */
    SUCCEEDED,

    /** Statut definitif defavorable, explicitement renvoye par l'operateur. */
    FAILED,

    /** Budget de relance epuise sans reponse. Ni succes, ni echec : une ignorance. */
    UNRESOLVED;

    /** Un statut definitif ferme le dialogue : plus aucune relance n'a de sens. */
    public boolean isFinal() {
        return this == SUCCEEDED || this == FAILED;
    }

    public boolean needsPolling() {
        return this == PENDING || this == ACCEPTED;
    }
}
