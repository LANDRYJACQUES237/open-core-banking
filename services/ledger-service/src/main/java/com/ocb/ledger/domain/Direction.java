package com.ocb.ledger.domain;

import java.math.BigDecimal;

/**
 * Sens d'une ligne d'ecriture.
 *
 * <p>La direction porte le signe ; le montant reste toujours positif. C'est le modele
 * comptable canonique, et il vaut mieux que l'alternative (un montant signe) pour une
 * raison pratique : avec un montant signe, un credit de -1000 et un debit de 1000 sont
 * arithmetiquement interchangeables, et une erreur de signe passe inapercue. Ici, une
 * ligne dit explicitement ce qu'elle fait.
 */
public enum Direction {

    /** Debit. Augmente un compte d'actif ou de charge, diminue un compte de passif ou de produit. */
    DR,

    /** Credit. Augmente un compte de passif ou de produit, diminue un compte d'actif ou de charge. */
    CR;

    public Direction opposite() {
        return this == DR ? CR : DR;
    }

    /**
     * Convertit un montant positif en valeur signee dans le sens debiteur.
     * C'est la convention retenue en base par la colonne generee {@code signed_amount} :
     * la somme des valeurs signees d'une ecriture equilibree vaut exactement zero.
     */
    public BigDecimal signed(BigDecimal positiveAmount) {
        return this == DR ? positiveAmount : positiveAmount.negate();
    }
}
