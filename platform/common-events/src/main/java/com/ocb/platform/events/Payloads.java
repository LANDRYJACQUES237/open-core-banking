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

    /**
     * Ordre de decaissement adresse a l'operateur.
     *
     * <p>La difference de fond avec {@link ProviderCollectionExecute} n'est pas dans les
     * champs mais dans ce qui a deja eu lieu : quand ce message part, le portefeuille du
     * client est <b>deja debite</b>. Un echec ne peut donc plus se traduire par un simple
     * abandon.
     *
     * @param payeeMsisdn    numero complet du beneficiaire. Comme pour l'encaissement,
     *                       c'est le seul message ou il circule en clair
     * @param idempotencyKey cle transmise a l'operateur : une retentative apres timeout ne
     *                       doit pas envoyer l'argent une seconde fois
     */
    public record ProviderDisbursementExecute(
            String transactionId,
            String providerCode,
            String amount,
            String currency,
            String payeeMsisdn,
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

    /**
     * @param walletAccountRef portefeuille concerne. Presence indispensable : le numero
     *                         masque ne permet de joindre personne, si bien qu'un
     *                         consommateur charge de prevenir le client n'aurait
     *                         autrement aucun moyen de savoir a qui s'adresser
     */
    public record PaymentCollectionFailed(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String walletAccountRef,
            String failureCode,
            String failureReason,
            String maskedMsisdn
    ) {
    }

    /**
     * Fonds engages, ordre parti.
     *
     * @param reservationEntryRef ecriture qui a debite le portefeuille vers le compte de
     *                            passage. Elle est publiee des maintenant parce que c'est
     *                            elle que la compensation contre-passera
     */
    public record PaymentDisbursementRequested(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String platformFee,
            String walletAccountRef,
            String providerCode,
            String reservationEntryRef,
            String maskedMsisdn
    ) {
    }

    /**
     * Decaissement livre.
     *
     * @param settlementEntryRef ecriture de livraison : le compte de passage se solde vers
     *                           le float operateur. Plus rien ne stationne en 1900 pour
     *                           cette transaction
     */
    public record PaymentDisbursementCompleted(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String platformFee,
            String providerFee,
            String walletAccountRef,
            String settlementEntryRef,
            String maskedMsisdn
    ) {
    }

    /**
     * Decaissement compense : le client a ete rembourse.
     *
     * <p>L'ecriture d'origine n'est ni modifiee ni supprimee — le grand livre est
     * immuable. La compensation est une ecriture supplementaire, de sens inverse, et les
     * deux reste visibles sur le releve du client. C'est voulu : un remboursement est un
     * fait, pas l'effacement d'un fait.
     */
    public record PaymentDisbursementReversed(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String walletAccountRef,
            String reservationEntryRef,
            String reversalEntryRef,
            String failureCode,
            String failureReason,
            String maskedMsisdn
    ) {
    }

    /**
     * Transfert accompli.
     *
     * @param ledgerEntryRef ecriture unique et equilibree. Il n'y en a jamais deux : un
     *                       transfert ne traverse aucune frontiere de service, donc rien
     *                       ne peut aboutir a moitie
     */
    public record PaymentTransferCompleted(
            String transactionId,
            String externalRef,
            String amount,
            String currency,
            String platformFee,
            String fromWalletAccountRef,
            String toWalletAccountRef,
            String ledgerEntryRef
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
