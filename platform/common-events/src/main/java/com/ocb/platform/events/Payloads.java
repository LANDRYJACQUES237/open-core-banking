package com.ocb.platform.events;

import java.time.Instant;

/**
 * Charges utiles des evenements, une par entree de
 * {@code contracts/events/payloads.schema.json}.
 *
 * <p>Regroupees dans un seul fichier, en miroir du catalogue de schemas : la surface
 * evenementielle complete se lit d'un coup d'oeil, et la correspondance avec le contrat
 * se verifie ligne a ligne.
 *
 * <p><b>Les montants sont des chaines, jamais des nombres.</b> Un nombre JSON est parse en
 * {@code double} par de nombreux clients, ce qui detruit la precision d'un montant sans
 * lever la moindre erreur. Ces types sont des DTO de transport : la conversion vers
 * {@code Money} se fait a la frontiere du service qui les recoit, une seule fois.
 */
public final class Payloads {

    private Payloads() {
    }

    // --- Commandes vers l'operateur --------------------------------------------------

    /**
     * @param payerMsisdn    numero complet. C'est le <b>seul</b> message ou il circule en
     *                       clair, parce que provider-service en a besoin pour appeler
     *                       l'operateur. Partout ailleurs il est masque
     * @param idempotencyKey cle transmise a l'operateur : une retentative apres timeout ne
     *                       doit pas creer un second paiement chez lui
     */
    public record ProviderCollectionExecute(
            String transactionId,
            String providerCode,
            String amount,
            String currency,
            String payerMsisdn,
            String externalRef,
            String idempotencyKey
    ) {
    }

    // --- Issues d'operations operateur -----------------------------------------------

    /** L'operateur a accepte la demande. Le resultat reste inconnu a ce stade. */
    public record ProviderOperationAccepted(
            String transactionId,
            String providerCode,
            String providerRef,
            Instant acceptedAt
    ) {
    }

    /**
     * Statut definitif favorable.
     *
     * <p>N'est jamais emis a la suite d'un timeout. Une absence de reponse n'autorise
     * aucune conclusion : l'argent a peut-etre bouge.
     *
     * @param providerFee commission prelevee par l'operateur, qui explique l'ecart entre
     *                    le montant demande et ce qui arrive reellement sur notre float
     * @param resolvedBy  chemin par lequel le statut a ete obtenu. Le callback donne la
     *                    rapidite, le polling la certitude
     */
    public record ProviderOperationSucceeded(
            String transactionId,
            String providerCode,
            String providerRef,
            String providerFee,
            String currency,
            Instant settledAt,
            String resolvedBy
    ) {
    }

    /** Statut definitif defavorable, explicitement renvoye par l'operateur. */
    public record ProviderOperationFailed(
            String transactionId,
            String providerCode,
            String providerRef,
            String errorCode,
            String errorMessage,
            String resolvedBy
    ) {
    }

    /**
     * Budget de polling epuise sans statut definitif.
     *
     * <p>Ne signifie <b>pas</b> echec. La transaction passe en revue manuelle, jamais en
     * echec : conclure a l'echec alors que l'argent est peut-etre parti est la faute la
     * plus couteuse d'un systeme de paiement.
     */
    public record ProviderOperationUnresolved(
            String transactionId,
            String providerCode,
            String providerRef,
            int pollAttempts,
            String lastKnownStatus
    ) {
    }

    // --- Cycle de vie d'une transaction ----------------------------------------------

    public record PaymentCollectionRequested(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String platformFee,
            String walletAccountRef,
            String providerCode,
            String maskedMsisdn
    ) {
    }

    /**
     * @param ledgerEntryRef reference de l'ecriture comptable. La porter dans l'evenement
     *                       rend le mouvement verifiable dans le grand livre par un
     *                       consommateur qui n'a pas acces a la base de payment-service
     */
    public record PaymentCollectionCompleted(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String platformFee,
            String providerFee,
            String walletAccountRef,
            String ledgerEntryRef,
            String maskedMsisdn
    ) {
    }

    public record PaymentCollectionFailed(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String failureCode,
            String failureReason,
            String maskedMsisdn
    ) {
    }

    public record PaymentManualReviewRequired(
            String transactionId,
            String externalRef,
            String status,
            String reason
    ) {
    }
}
