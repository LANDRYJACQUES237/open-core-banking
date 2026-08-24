package com.ocb.provider.domain;

import com.ocb.platform.domain.money.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Etat du dialogue avec un operateur pour une transaction donnee.
 *
 * @param providerIdempotencyKey cle transmise a l'operateur. Elle est ce qui rend une
 *                               relance apres expiration du delai inoffensive : sans
 *                               elle, chaque retentative creerait un nouveau paiement
 * @param nextPollAt             nul quand plus aucune relance n'est prevue, soit parce
 *                               que l'operation est resolue, soit parce que le budget
 *                               est epuise
 */
public record ProviderOperation(
        UUID id,
        UUID transactionId,
        ProviderCode providerCode,
        OperationType type,
        String externalRef,
        String providerIdempotencyKey,
        String payerMsisdn,
        Money amount,
        String providerRef,
        OperationStatus status,
        Money providerFee,
        String errorCode,
        String errorMessage,
        String lastError,
        int attemptCount,
        int pollAttempts,
        Instant lastPolledAt,
        Instant nextPollAt,
        boolean pollBudgetExhausted,
        Instant createdAt,
        Instant updatedAt,
        long version
) {

    public boolean isFinal() {
        return status.isFinal();
    }

    /**
     * Une operation resolue ou abandonnee n'est plus relancee.
     *
     * <p>C'est ce qui fait qu'un rappel recu annule les relances restantes : en passant
     * l'operation a un statut definitif, il rend {@code nextPollAt} nul, et
     * l'ordonnanceur ne la voit plus.
     */
    public boolean awaitsPolling() {
        return status.needsPolling() && !pollBudgetExhausted;
    }
}
