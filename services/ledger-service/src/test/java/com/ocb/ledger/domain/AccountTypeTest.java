package com.ocb.ledger.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTypeTest {

    @ParameterizedTest
    @CsvSource({
            "ASSET, DR",
            "EXPENSE, DR",
            "LIABILITY, CR",
            "REVENUE, CR",
            "EQUITY, CR"
    })
    @DisplayName("le cote normal decoule du type, il n'est jamais une donnee libre")
    void normalSideFollowsType(AccountType type, Direction expected) {
        assertThat(type.normalSide()).isEqualTo(expected);
    }

    @Test
    @DisplayName("un portefeuille client credite affiche un solde positif")
    void customerWalletBalanceIsPresentedPositive() {
        // Crediter un portefeuille de 10 000 produit une somme brute de -10 000, puisque
        // la somme brute est orientee au debit. Le solde presente doit valoir +10 000 :
        // la dette envers le client est bien de 10 000. Sans cette conversion, tous les
        // comptes de passif afficheraient un solde negatif.
        BigDecimal raw = new BigDecimal("-10000");
        assertThat(AccountType.LIABILITY.fromRawDebitBalance(raw)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("un compte de float debite affiche un solde positif")
    void assetBalanceKeepsItsSign() {
        assertThat(AccountType.ASSET.fromRawDebitBalance(new BigDecimal("9850")))
                .isEqualByComparingTo("9850");
    }

    @Test
    @DisplayName("un portefeuille a decouvert affiche bien un solde negatif")
    void overdrawnWalletIsNegative() {
        // Cas qui doit rester possible a representer : le grand livre enregistre ce qui
        // s'est passe. Interdire le decouvert est une regle du service de paiement,
        // pas de la comptabilite.
        assertThat(AccountType.LIABILITY.fromRawDebitBalance(new BigDecimal("500")))
                .isEqualByComparingTo("-500");
    }
}
