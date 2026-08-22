package com.ocb.payment.domain;

/** Codes d'erreur stables, destines au code appelant plutot qu'a un humain. */
public final class PaymentErrors {

    private PaymentErrors() {
    }

    public static final String TRANSACTION_NOT_FOUND = "PAYMENT_TRANSACTION_NOT_FOUND";

    /** Meme cle d'idempotence, contenu different. Bug appelant, pas un rejeu. */
    public static final String IDEMPOTENCY_KEY_REUSED = "PAYMENT_IDEMPOTENCY_KEY_REUSED";

    /** Une requete portant cette cle est en cours de traitement. L'appelant peut reessayer. */
    public static final String IDEMPOTENT_REQUEST_IN_PROGRESS = "PAYMENT_REQUEST_IN_PROGRESS";

    public static final String INVALID_AMOUNT = "PAYMENT_INVALID_AMOUNT";
    public static final String UNSUPPORTED_PROVIDER = "PAYMENT_UNSUPPORTED_PROVIDER";

    /** Le grand livre a refuse l'ecriture. */
    public static final String LEDGER_REJECTED = "PAYMENT_LEDGER_REJECTED";

    /** Le grand livre est injoignable. Distinct d'un refus : rien ne permet de conclure. */
    public static final String LEDGER_UNAVAILABLE = "PAYMENT_LEDGER_UNAVAILABLE";
}
