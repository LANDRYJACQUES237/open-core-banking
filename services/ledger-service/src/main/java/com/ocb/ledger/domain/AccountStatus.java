package com.ocb.ledger.domain;

/** Cycle de vie d'un compte. Un compte n'est jamais supprime : ses ecritures doivent rester lisibles. */
public enum AccountStatus {

    ACTIVE,

    /** Lecture autorisee, ecriture refusee. Utilise pour une suspension temporaire. */
    FROZEN,

    /** Compte solde et ferme. Aucune nouvelle ecriture. */
    CLOSED;

    public boolean acceptsPostings() {
        return this == ACTIVE;
    }
}
