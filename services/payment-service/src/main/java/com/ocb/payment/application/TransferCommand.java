package com.ocb.payment.application;

/**
 * Demande de transfert entre deux portefeuilles.
 *
 * <p>Aucun code operateur, aucun numero de telephone : rien ne sort du systeme. C'est ce
 * qui distingue un transfert d'un decaissement, et ce qui explique qu'il n'ait pas besoin
 * de saga.
 */
public record TransferCommand(String externalRef,
                              String amount,
                              String currency,
                              String fromWalletAccountRef,
                              String toWalletAccountRef,
                              String idempotencyKey,
                              String clientId,
                              String correlationId) {
}
