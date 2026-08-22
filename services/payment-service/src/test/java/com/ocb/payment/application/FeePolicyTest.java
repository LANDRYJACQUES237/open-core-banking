package com.ocb.payment.application;

import com.ocb.platform.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class FeePolicyTest {

    @Test
    @DisplayName("1 % de 10 000 XAF vaut 100")
    void simplePercentage() {
        assertThat(policy(100, "0", null).forCollection(xaf("10000")))
                .isEqualTo(xaf("100"));
    }

    @Test
    @DisplayName("un frais fractionnaire est arrondi a l'echelle de la devise, pas rejete")
    void roundsToCurrencyScale() {
        // 1,5 % de 3 333 XAF vaut 49,995. Le XAF n'ayant aucune decimale, construire
        // Money avec cette valeur echouerait : Money refuse tout arrondi implicite.
        // L'arrondi doit donc etre fait ici, ou il est visible et teste.
        assertThatCode(() -> policy(150, "0", null).forCollection(xaf("3333")))
                .doesNotThrowAnyException();

        assertThat(policy(150, "0", null).forCollection(xaf("3333")))
                .isEqualTo(xaf("50"));
    }

    @Test
    @DisplayName("la part fixe s'ajoute a la part variable")
    void fixedPlusVariable() {
        assertThat(policy(100, "50", null).forCollection(xaf("10000")))
                .isEqualTo(xaf("150"));
    }

    @Test
    @DisplayName("le plafond s'applique")
    void capApplies() {
        assertThat(policy(100, "0", "500").forCollection(xaf("1000000")))
                .isEqualTo(xaf("500"));
    }

    @Test
    @DisplayName("des frais nuls restent possibles")
    void zeroFeeIsValid() {
        assertThat(policy(0, "0", null).forCollection(xaf("10000")))
                .isEqualTo(xaf("0"));
    }

    @Test
    @DisplayName("l'euro conserve ses deux decimales")
    void eurKeepsTwoDecimals() {
        Money fee = policy(150, "0", null)
                .forCollection(Money.parse("100.00", "EUR"));
        assertThat(fee.toPlainString()).isEqualTo("1.50");
    }

    private static FeePolicy policy(int basisPoints, String fixed, String cap) {
        FeePolicy policy = new FeePolicy();
        policy.setBasisPoints(basisPoints);
        policy.setFixed(new BigDecimal(fixed));
        policy.setCap(cap == null ? null : new BigDecimal(cap));
        return policy;
    }

    private static Money xaf(String amount) {
        return Money.parse(amount, "XAF");
    }
}
