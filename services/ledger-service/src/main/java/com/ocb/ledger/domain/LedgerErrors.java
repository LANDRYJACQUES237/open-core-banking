package com.ocb.ledger.domain;

/**
 * Codes d'erreur stables du grand livre.
 *
 * <p>Ils sont destines au code appelant, pas a un humain : un client peut brancher
 * dessus sans parser un message susceptible d'etre reformule. Les regrouper ici les
 * rend enumerables, donc documentables et testables exhaustivement.
 */
public final class LedgerErrors {

    private LedgerErrors() {
    }

    // --- Ecritures -------------------------------------------------------------------
    public static final String UNBALANCED_ENTRY = "LEDGER_UNBALANCED_ENTRY";
    public static final String TOO_FEW_LINES = "LEDGER_TOO_FEW_LINES";
    public static final String TOO_MANY_LINES = "LEDGER_TOO_MANY_LINES";
    public static final String MIXED_CURRENCY = "LEDGER_MIXED_CURRENCY";
    public static final String DUPLICATE_LINE_NO = "LEDGER_DUPLICATE_LINE_NO";
    public static final String ENTRY_NOT_FOUND = "LEDGER_ENTRY_NOT_FOUND";
    public static final String ENTRY_REF_TAKEN = "LEDGER_ENTRY_REF_TAKEN";

    // --- Contre-passation ------------------------------------------------------------
    public static final String ALREADY_REVERSED = "LEDGER_ALREADY_REVERSED";
    public static final String CANNOT_REVERSE_REVERSAL = "LEDGER_CANNOT_REVERSE_REVERSAL";

    // --- Comptes ---------------------------------------------------------------------
    public static final String ACCOUNT_NOT_FOUND = "LEDGER_ACCOUNT_NOT_FOUND";
    public static final String ACCOUNT_NOT_POSTABLE = "LEDGER_ACCOUNT_NOT_POSTABLE";
    public static final String ACCOUNT_NOT_ACTIVE = "LEDGER_ACCOUNT_NOT_ACTIVE";
    public static final String ACCOUNT_CURRENCY_MISMATCH = "LEDGER_ACCOUNT_CURRENCY_MISMATCH";
    public static final String ACCOUNT_NUMBER_TAKEN = "LEDGER_ACCOUNT_NUMBER_TAKEN";
    public static final String PARENT_ACCOUNT_NOT_FOUND = "LEDGER_PARENT_ACCOUNT_NOT_FOUND";

    // --- Idempotence -----------------------------------------------------------------
    /**
     * Meme cle d'idempotence, contenu different. Ce n'est pas un rejeu, c'est un bug
     * appelant : deux operations distinctes partagent une cle qui devait etre unique.
     */
    public static final String IDEMPOTENCY_KEY_REUSED = "LEDGER_IDEMPOTENCY_KEY_REUSED";
}
