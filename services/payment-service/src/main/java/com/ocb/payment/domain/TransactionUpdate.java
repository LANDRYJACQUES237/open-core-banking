package com.ocb.payment.domain;

import com.ocb.platform.domain.money.Money;

/**
 * Champs a mettre a jour en meme temps qu'une transition d'etat.
 *
 * <p>Les regrouper avec la transition n'est pas cosmetique : cela garantit qu'on ne peut
 * pas enregistrer une reference operateur sans passer par la machine a etats, ni changer
 * d'etat en oubliant la donnee qui justifie ce changement. Un champ {@code null} signifie
 * "inchange", jamais "effacer".
 */
public record TransactionUpdate(
        String providerRef,
        Money providerFee,
        String ledgerEntryRef,
        String failureCode,
        String failureReason
) {

    private static final TransactionUpdate NONE = new TransactionUpdate(null, null, null, null, null);

    public static TransactionUpdate none() {
        return NONE;
    }

    public static TransactionUpdate providerRef(String providerRef) {
        return new TransactionUpdate(providerRef, null, null, null, null);
    }

    public static TransactionUpdate settled(String providerRef, Money providerFee) {
        return new TransactionUpdate(providerRef, providerFee, null, null, null);
    }

    public static TransactionUpdate posted(String ledgerEntryRef) {
        return new TransactionUpdate(null, null, ledgerEntryRef, null, null);
    }

    public static TransactionUpdate failed(String failureCode, String failureReason) {
        return new TransactionUpdate(null, null, null, failureCode, failureReason);
    }
}
