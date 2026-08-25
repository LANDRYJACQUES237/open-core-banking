package com.ocb.payment.domain;

/**
 * Comptes du grand livre auxquels ce service se refere.
 *
 * <p>Ils etaient jusqu'ici des constantes privees dispersees. Le decaissement en utilise
 * les memes depuis deux classes differentes : un numero recopie finit par diverger, et un
 * decaissement comptabilise sur le mauvais compte ne produit aucune erreur — seulement un
 * bilan faux.
 */
public final class LedgerAccounts {

    private LedgerAccounts() {
    }

    /** Produits : nos commissions. */
    public static final String FEE_INCOME = "4100";

    /** Charges : la commission prelevee par l'operateur. */
    public static final String PROVIDER_COST = "5100";

    /**
     * Compte de passage des decaissements.
     *
     * <p>Il porte les fonds engages : deja debites du portefeuille du client, pas encore
     * livres par l'operateur. C'est ce qui rend la saga lisible sans table d'etat
     * supplementaire — <b>tout montant qui stationne ici est une question ouverte</b>, et
     * son solde est la liste des decaissements en vol.
     */
    public static final String DISBURSEMENT_SUSPENSE = "1900";
}
