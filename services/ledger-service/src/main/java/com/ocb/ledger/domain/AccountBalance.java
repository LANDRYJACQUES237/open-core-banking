package com.ocb.ledger.domain;

import com.ocb.platform.domain.money.Money;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Solde d'un compte a un point precis du grand livre.
 *
 * <p>Ce n'est pas une donnee stockee : c'est le resultat d'un calcul, accompagne du
 * numero de la derniere ecriture prise en compte. Ce numero est ce qui rend la reponse
 * verifiable — un appelant peut savoir exactement a quel etat du grand livre le solde
 * correspond, au lieu de recevoir un nombre sans contexte.
 */
public record AccountBalance(
        String accountNumber,
        Money balance,
        long entrySeq,
        OffsetDateTime computedAt
) {

    /**
     * Convertit une somme brute (au sens debiteur, telle que stockee) en solde presente
     * dans le sens normal du compte.
     *
     * @param rawBalance somme des {@code signed_amount}, positive au debit
     */
    public static AccountBalance fromRaw(LedgerAccount account,
                                         BigDecimal rawBalance,
                                         long entrySeq,
                                         OffsetDateTime computedAt) {
        BigDecimal oriented = account.type().fromRawDebitBalance(rawBalance);
        return new AccountBalance(
                account.accountNumber(),
                Money.of(oriented, account.currency()),
                entrySeq,
                computedAt);
    }
}
