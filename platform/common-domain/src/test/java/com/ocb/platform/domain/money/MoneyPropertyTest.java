package com.ocb.platform.domain.money;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proprietes de {@link Money}, verifiees sur des valeurs generees.
 *
 * <p>Les tests par l'exemple valident les cas auxquels on a pense. Les proprietes
 * valident ce a quoi on n'a pas pense — c'est precisement la ou se cachent les erreurs
 * d'arrondi et de signe dans un systeme financier.
 */
class MoneyPropertyTest {

    private static final Currency XAF = Currency.getInstance("XAF");

    @Property
    void additionThenSubtractionRestoresTheOriginal(@ForAll @IntRange(min = 0, max = 1_000_000_000) int a,
                                                    @ForAll @IntRange(min = 0, max = 1_000_000_000) int b) {
        Money first = Money.of(BigDecimal.valueOf(a), XAF);
        Money second = Money.of(BigDecimal.valueOf(b), XAF);
        assertThat(first.add(second).subtract(second)).isEqualTo(first);
    }

    @Property
    void additionIsCommutative(@ForAll @IntRange(min = 0, max = 1_000_000_000) int a,
                               @ForAll @IntRange(min = 0, max = 1_000_000_000) int b) {
        Money first = Money.of(BigDecimal.valueOf(a), XAF);
        Money second = Money.of(BigDecimal.valueOf(b), XAF);
        assertThat(first.add(second)).isEqualTo(second.add(first));
    }

    @Property
    void negationIsItsOwnInverse(@ForAll @IntRange(min = -1_000_000, max = 1_000_000) int a) {
        Money money = Money.of(BigDecimal.valueOf(a), XAF);
        assertThat(money.negate().negate()).isEqualTo(money);
    }

    @Property
    void serialisationRoundTripsWithoutLoss(@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int a) {
        Money money = Money.of(BigDecimal.valueOf(a), XAF);
        // Le transport se fait en chaine : il doit etre exactement reversible, sinon
        // un montant change de valeur en traversant une frontiere de service.
        assertThat(Money.parse(money.toPlainString(), "XAF")).isEqualTo(money);
    }

    @Property
    void plainStringNeverUsesScientificNotation(@ForAll @IntRange(min = 0, max = Integer.MAX_VALUE) int a) {
        assertThat(Money.of(BigDecimal.valueOf(a), XAF).toPlainString())
                .doesNotContain("E")
                .doesNotContain("e");
    }
}
