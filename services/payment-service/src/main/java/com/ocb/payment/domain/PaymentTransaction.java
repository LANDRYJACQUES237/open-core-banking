package com.ocb.payment.domain;

import com.ocb.platform.domain.money.Money;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Etat d'une operation de paiement.
 *
 * <p>Ce service ne detient aucune verite financiere — les soldes et les ecritures
 * appartiennent au grand livre. Il detient l'etat d'avancement, qui est une donnee
 * differente et tout aussi critique : c'est elle qui dit si l'argent a bouge, s'il est en
 * vol, ou si personne ne sait.
 *
 * @param providerFee     commission de l'operateur, inconnue tant qu'il n'a pas conclu
 * @param maskedMsisdn    forme masquee uniquement ; le numero complet n'est jamais conserve
 * @param ledgerEntryRef  reference de l'ecriture comptable, une fois le mouvement enregistre
 */
public record PaymentTransaction(
        UUID id,
        String externalRef,
        TransactionType type,
        TransactionStatus status,
        Money amount,
        Money platformFee,
        Money providerFee,
        String walletAccountRef,
        ProviderCode providerCode,
        String maskedMsisdn,
        String providerRef,
        String ledgerEntryRef,
        String failureCode,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        long version
) {

    /**
     * Montant effectivement credite au portefeuille du client.
     *
     * <p>Le client envoie {@code amount} ; nos frais sont preleves au passage. C'est ce
     * montant, et non celui demande, qui apparait sur son portefeuille.
     */
    public Money walletCredit() {
        return amount.subtract(platformFee);
    }

    /**
     * Montant reellement credite sur notre float chez l'operateur.
     *
     * <p>L'operateur preleve sa propre commission avant de nous crediter : on ne recoit
     * pas le montant demande. Ignorer cet ecart produirait une ecriture desequilibree,
     * refusee par le grand livre — ce qui vaut mieux que de ne pas s'en apercevoir.
     */
    public Money floatCredit() {
        return providerFee == null ? amount : amount.subtract(providerFee);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }
}
