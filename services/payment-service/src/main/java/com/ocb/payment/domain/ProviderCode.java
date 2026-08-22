package com.ocb.payment.domain;

/**
 * Operateurs Mobile Money supportes.
 *
 * <p>{@code floatAccount} designe le compte du grand livre qui porte notre encaisse chez
 * cet operateur. Le placer ici plutot que dans une configuration evite qu'un encaissement
 * MTN soit comptabilise sur le float Orange a la suite d'une erreur de parametrage.
 */
public enum ProviderCode {

    MTN_MOMO("1100"),
    ORANGE_MONEY("1101");

    private final String floatAccount;

    ProviderCode(String floatAccount) {
        this.floatAccount = floatAccount;
    }

    public String floatAccount() {
        return floatAccount;
    }
}
