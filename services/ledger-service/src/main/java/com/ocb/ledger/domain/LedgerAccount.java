package com.ocb.ledger.domain;

import com.ocb.platform.domain.error.InvariantViolationException;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.UUID;

/**
 * Compte du grand livre.
 *
 * <p>Ce type ne porte aucune donnee personnelle. {@code ownerRef} est une reference
 * opaque : le grand livre sait qu'un compte appartient a {@code wallet-c}, il ne sait
 * pas qui est {@code wallet-c}, ni son numero de telephone, ni son statut KYC. C'est
 * cette frontiere qui permettra d'extraire un service client plus tard sans toucher
 * a la comptabilite.
 */
public record LedgerAccount(
        UUID id,
        String accountNumber,
        AccountType type,
        Currency currency,
        String ownerRef,
        String name,
        AccountStatus status,
        boolean postable,
        UUID parentId,
        OffsetDateTime openedAt
) {

    public Direction normalSide() {
        return type.normalSide();
    }

    /**
     * Verifie qu'une ecriture peut viser ce compte.
     *
     * <p>Deux refus distincts, volontairement separes : un compte de regroupement n'est
     * pas fait pour recevoir des ecritures (erreur de conception de l'appelant), tandis
     * qu'un compte gele en refuse temporairement (etat metier). Les confondre priverait
     * l'appelant de l'information dont il a besoin pour reagir.
     */
    public void requireAcceptsPostings() {
        if (!postable) {
            throw new InvariantViolationException(
                    LedgerErrors.ACCOUNT_NOT_POSTABLE,
                    ("Le compte %s est un compte de regroupement : une ecriture doit designer "
                            + "un sous-compte precis").formatted(accountNumber));
        }
        if (!status.acceptsPostings()) {
            throw new InvariantViolationException(
                    LedgerErrors.ACCOUNT_NOT_ACTIVE,
                    "Le compte %s est %s et n'accepte pas d'ecriture".formatted(accountNumber, status));
        }
    }

    public void requireCurrency(Currency expected) {
        if (!currency.equals(expected)) {
            throw new InvariantViolationException(
                    LedgerErrors.ACCOUNT_CURRENCY_MISMATCH,
                    "Le compte %s est tenu en %s, l'ecriture est en %s"
                            .formatted(accountNumber, currency.getCurrencyCode(), expected.getCurrencyCode()));
        }
    }
}
