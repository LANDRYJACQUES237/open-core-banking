package com.ocb.platform.domain.money;

import com.ocb.platform.domain.error.InvariantViolationException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Montant monetaire. Le seul type autorise a representer de l'argent dans la plateforme.
 *
 * <p>Trois proprietes, chacune motivee par un bug reel des systemes financiers :
 *
 * <ol>
 *   <li><b>Aucun constructeur n'accepte {@code double}.</b> {@code 0.1 + 0.2} vaut
 *       {@code 0.30000000000000004} en virgule flottante. Un grand livre qui autorise
 *       le {@code double} finit desequilibre d'un centime, et personne ne sait quand
 *       ni ou. L'absence de surcharge {@code double} rend l'erreur impossible a ecrire.
 *
 *   <li><b>L'echelle est imposee par la devise, sans arrondi implicite.</b> Le XAF n'a
 *       aucune decimale : {@code 1500.50 XAF} n'est pas un montant a arrondir, c'est un
 *       bug appelant. La normalisation utilise {@link RoundingMode#UNNECESSARY}, qui leve
 *       plutot que de corriger silencieusement. Les echelles viennent de
 *       {@link Currency#getDefaultFractionDigits()} : la JDK connait deja l'ISO 4217,
 *       il n'y a aucune raison de recopier une table de devises qui divergera.
 *
 *   <li><b>Les operations entre devises differentes sont refusees.</b> Additionner des
 *       XAF a des EUR n'a pas de sens sans taux de change, et la v1 n'en a pas.
 * </ol>
 *
 * <p>Le type est un {@code record}, donc {@code equals} compare les composants.
 * {@link BigDecimal#equals} est sensible a l'echelle ({@code 10} != {@code 10.00}),
 * ce qui serait un piege classique — la normalisation en constructeur compact garantit
 * que deux montants de meme valeur et meme devise ont toujours la meme echelle, donc
 * que {@code equals} se comporte comme attendu.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /**
     * Forme decimale acceptee a la frontiere. Volontairement plus stricte que
     * {@link BigDecimal#BigDecimal(String)}, qui accepte la notation scientifique
     * ({@code 1E+5}) : on ne veut pas d'un montant dont la lecture depend du parseur.
     */
    private static final Pattern DECIMAL = Pattern.compile("^-?(0|[1-9][0-9]{0,17})(\\.[0-9]{1,6})?$");

    public static final String ERR_MALFORMED = "MONEY_MALFORMED_AMOUNT";
    public static final String ERR_UNKNOWN_CURRENCY = "MONEY_UNKNOWN_CURRENCY";
    public static final String ERR_SCALE = "MONEY_INVALID_SCALE";
    public static final String ERR_CURRENCY_MISMATCH = "MONEY_CURRENCY_MISMATCH";

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");

        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) {
            // -1 signale une pseudo-devise ISO 4217 (XXX, XTS, XAU...) : pas de montant transactionnel.
            throw new InvariantViolationException(
                    ERR_UNKNOWN_CURRENCY,
                    "La devise %s n'a pas d'echelle transactionnelle definie".formatted(currency.getCurrencyCode()));
        }
        try {
            amount = amount.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new InvariantViolationException(
                    ERR_SCALE,
                    "Le montant %s ne respecte pas l'echelle de %s (%d decimale(s)) ; aucun arrondi implicite n'est applique"
                            .formatted(amount.toPlainString(), currency.getCurrencyCode(), scale),
                    e);
        }
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyOf(currencyCode));
    }

    /**
     * Parse un montant recu du monde exterieur. C'est le seul point d'entree depuis une
     * chaine, et il est strict : ce qui n'est pas une decimale simple est refuse ici
     * plutot que de circuler dans le systeme sous une forme ambigue.
     */
    public static Money parse(String amount, String currencyCode) {
        if (amount == null || !DECIMAL.matcher(amount).matches()) {
            throw new InvariantViolationException(
                    ERR_MALFORMED,
                    "Montant illisible : %s".formatted(amount));
        }
        return new Money(new BigDecimal(amount), currencyOf(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Currency currencyOf(String code) {
        if (code == null || code.length() != 3) {
            throw new InvariantViolationException(
                    ERR_UNKNOWN_CURRENCY, "Code devise invalide : %s".formatted(code));
        }
        try {
            return Currency.getInstance(code);
        } catch (IllegalArgumentException e) {
            throw new InvariantViolationException(
                    ERR_UNKNOWN_CURRENCY, "Devise inconnue de l'ISO 4217 : %s".formatted(code), e);
        }
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money abs() {
        return new Money(amount.abs(), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** Representation destinee au transport JSON : jamais de notation scientifique. */
    public String toPlainString() {
        return amount.toPlainString();
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!currency.equals(other.currency)) {
            throw new InvariantViolationException(
                    ERR_CURRENCY_MISMATCH,
                    "Operation entre devises differentes : %s et %s"
                            .formatted(currencyCode(), other.currencyCode()));
        }
    }

    @Override
    public String toString() {
        return amount.toPlainString() + " " + currency.getCurrencyCode();
    }
}
