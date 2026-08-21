package com.ocb.platform.domain.money;

import com.ocb.platform.domain.error.InvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    private static final Currency XAF = Currency.getInstance("XAF");
    private static final Currency EUR = Currency.getInstance("EUR");

    @Nested
    @DisplayName("Echelle imposee par la devise")
    class Scale {

        @Test
        @DisplayName("le XAF n'a aucune decimale, la JDK le sait deja")
        void xafHasNoDecimals() {
            assertThat(XAF.getDefaultFractionDigits()).isZero();
            assertThat(EUR.getDefaultFractionDigits()).isEqualTo(2);
        }

        @Test
        @DisplayName("un montant XAF a decimales est refuse, pas arrondi")
        void rejectsFractionalXaf() {
            assertThatThrownBy(() -> Money.of(new BigDecimal("1500.50"), XAF))
                    .isInstanceOf(InvariantViolationException.class)
                    .hasMessageContaining("echelle")
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(Money.ERR_SCALE);
        }

        @Test
        @DisplayName("les decimales nulles sont acceptees : 1500.00 XAF vaut bien 1500")
        void acceptsZeroFraction() {
            assertThat(Money.of(new BigDecimal("1500.00"), XAF).toPlainString()).isEqualTo("1500");
        }

        @Test
        @DisplayName("l'echelle est normalisee, donc equals se comporte comme attendu")
        void equalityIgnoresInputScale() {
            // BigDecimal.equals est sensible a l'echelle : sans normalisation,
            // 10000 et 10000.00 seraient consideres differents. C'est le piege que
            // la normalisation en constructeur compact supprime.
            assertThat(new BigDecimal("10000")).isNotEqualTo(new BigDecimal("10000.00"));
            assertThat(Money.of(new BigDecimal("10000"), XAF))
                    .isEqualTo(Money.of(new BigDecimal("10000.00"), XAF));
        }

        @Test
        @DisplayName("l'euro conserve ses deux decimales")
        void eurKeepsTwoDecimals() {
            assertThat(Money.of(new BigDecimal("12.5"), EUR).toPlainString()).isEqualTo("12.50");
        }
    }

    @Nested
    @DisplayName("Lecture depuis l'exterieur")
    class Parsing {

        @Test
        @DisplayName("la notation scientifique est refusee")
        void rejectsScientificNotation() {
            // new BigDecimal("1E+5") vaut 100000 : accepte tel quel, un montant deviendrait
            // dependant du parseur du client. On refuse a la frontiere.
            assertThat(new BigDecimal("1E+5")).isEqualByComparingTo("100000");
            assertThatThrownBy(() -> Money.parse("1E+5", "XAF"))
                    .isInstanceOf(InvariantViolationException.class)
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(Money.ERR_MALFORMED);
        }

        @Test
        @DisplayName("les formes non decimales sont refusees")
        void rejectsGarbage() {
            for (String bad : new String[]{"", " ", "abc", "10 000", "1,5", "+10", "0x10", null}) {
                assertThatThrownBy(() -> Money.parse(bad, "XAF"))
                        .as("montant refuse : %s", bad)
                        .isInstanceOf(InvariantViolationException.class);
            }
        }

        @Test
        @DisplayName("une devise inconnue de l'ISO 4217 est refusee")
        void rejectsUnknownCurrency() {
            assertThatThrownBy(() -> Money.parse("100", "ZZZ"))
                    .isInstanceOf(InvariantViolationException.class)
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(Money.ERR_UNKNOWN_CURRENCY);
        }
    }

    @Nested
    @DisplayName("Operations")
    class Operations {

        @Test
        @DisplayName("additionner deux devises differentes est refuse")
        void rejectsCurrencyMismatch() {
            Money xaf = Money.of(new BigDecimal("100"), XAF);
            Money eur = Money.of(new BigDecimal("100"), EUR);
            assertThatThrownBy(() -> xaf.add(eur))
                    .isInstanceOf(InvariantViolationException.class)
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(Money.ERR_CURRENCY_MISMATCH);
        }

        @Test
        @DisplayName("addition et soustraction restent exactes")
        void arithmeticIsExact() {
            Money a = Money.of(new BigDecimal("10000"), XAF);
            Money b = Money.of(new BigDecimal("9900"), XAF);
            assertThat(a.subtract(b)).isEqualTo(Money.of(new BigDecimal("100"), XAF));
            assertThat(a.subtract(b).add(b)).isEqualTo(a);
        }

        @Test
        @DisplayName("0.1 + 0.2 vaut exactement 0.3, ce que le double ne sait pas faire")
        void noFloatingPointDrift() {
            assertThat(0.1d + 0.2d).isNotEqualTo(0.3d);

            Currency usd = Currency.getInstance("USD");
            Money sum = Money.of(new BigDecimal("0.10"), usd).add(Money.of(new BigDecimal("0.20"), usd));
            assertThat(sum).isEqualTo(Money.of(new BigDecimal("0.30"), usd));
            assertThat(sum.toPlainString()).isEqualTo("0.30");
        }

        @Test
        @DisplayName("le signe est interrogeable sans exposer le BigDecimal")
        void signs() {
            assertThat(Money.of(new BigDecimal("1"), XAF).isPositive()).isTrue();
            assertThat(Money.zero(XAF).isZero()).isTrue();
            assertThat(Money.of(new BigDecimal("-1"), XAF).isNegative()).isTrue();
        }
    }
}
