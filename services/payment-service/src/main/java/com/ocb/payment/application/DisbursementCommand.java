package com.ocb.payment.application;

import com.ocb.payment.domain.ProviderCode;

/**
 * Demande de decaissement telle qu'elle arrive de l'appelant.
 *
 * @param clientId identite verifiee de l'appelant, qui borne la portee de la cle
 *                 d'idempotence
 */
public record DisbursementCommand(String externalRef,
                                  String amount,
                                  String currency,
                                  String payeeMsisdn,
                                  String walletAccountRef,
                                  ProviderCode providerCode,
                                  String idempotencyKey,
                                  String clientId,
                                  String correlationId) {
}
