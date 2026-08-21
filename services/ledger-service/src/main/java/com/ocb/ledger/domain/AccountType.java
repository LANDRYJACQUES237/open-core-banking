package com.ocb.ledger.domain;

/**
 * Nature comptable d'un compte, qui determine son cote normal donc le signe de son solde.
 *
 * <p>Le cas a retenir est {@link #LIABILITY}. Un portefeuille client est un compte de
 * passif : l'argent depose par un client est une dette de la plateforme envers lui.
 * Le crediter augmente cette dette. Modeliser un portefeuille en {@link #ASSET} produit
 * un bilan faux, et le systeme continue de fonctionner sans jamais signaler l'erreur —
 * c'est pourquoi la contrainte est aussi inscrite en base.
 */
public enum AccountType {

    /** Ce que la plateforme possede : float chez un operateur, compte bancaire, compte de passage. */
    ASSET(Direction.DR),

    /** Ce que la plateforme doit : portefeuilles clients, encaissements non affectes. */
    LIABILITY(Direction.CR),

    /** Ce que la plateforme gagne : commissions percues. */
    REVENUE(Direction.CR),

    /** Ce que la plateforme depense : commissions prelevees par l'operateur. */
    EXPENSE(Direction.DR),

    /** Capitaux propres. Non utilise en v1, present pour completude du plan de comptes. */
    EQUITY(Direction.CR);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    public Direction normalSide() {
        return normalSide;
    }

    /**
     * Convertit un solde exprime dans le sens debiteur (la somme brute des
     * {@code signed_amount}) vers le sens normal du compte.
     *
     * <p>Un portefeuille client credite de 10 000 a une somme brute de -10 000 ;
     * son solde presente doit valoir +10 000, parce que la dette est bien de 10 000.
     * Sans cette conversion, tous les comptes de passif afficheraient un solde negatif.
     */
    public java.math.BigDecimal fromRawDebitBalance(java.math.BigDecimal rawBalance) {
        return normalSide == Direction.DR ? rawBalance : rawBalance.negate();
    }
}
