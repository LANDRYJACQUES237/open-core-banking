package com.ocb.payment.application;

import com.ocb.payment.domain.ProviderCode;

/**
 * Demande d'encaissement telle que recue.
 *
 * <p>{@code payerMsisdn} arrive ici sous forme de chaine brute et est immediatement
 * converti en {@code Msisdn} par le service : c'est le dernier endroit ou le numero
 * existe sans protection contre la journalisation accidentelle.
 */
public record CollectionCommand(
        String externalRef,
        String amount,
        String currency,
        String payerMsisdn,
        String walletAccountRef,
        ProviderCode providerCode,
        String idempotencyKey,
        String clientId,
        String correlationId
) {
}
