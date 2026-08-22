package com.ocb.payment.application;

import com.ocb.platform.domain.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calcul des frais de la plateforme.
 *
 * <p>Deux points de vigilance, tous deux sources classiques d'ecarts d'un centime qui
 * finissent en desequilibre comptable.
 *
 * <p><b>Le mode d'arrondi est explicite.</b> {@code HALF_UP} et non le defaut : une
 * division de {@link BigDecimal} sans mode d'arrondi leve, et une division avec un mode
 * implicite serait un choix invisible dans le code alors qu'il decide de qui gagne le
 * centime.
 *
 * <p><b>L'arrondi respecte l'echelle de la devise.</b> Un frais de 1,5 % sur 10 000 XAF
 * vaut 150 exactement, mais sur 3 333 XAF il vaudrait 49,995 : le XAF n'ayant aucune
 * decimale, {@link Money} refuserait ce montant. L'arrondi est donc applique <b>avant</b>
 * la construction du montant, ici, ou il est visible et testable — plutot que subi plus
 * loin sous forme d'exception.
 */
@Component
@ConfigurationProperties(prefix = "ocb.fees.collection")
public class FeePolicy {

    /** Part variable, en points de base. 100 pb = 1 %. */
    private int basisPoints = 100;

    /** Part fixe, exprimee dans l'unite de la devise. */
    private BigDecimal fixed = BigDecimal.ZERO;

    /** Plafond, ou {@code null} pour aucun. */
    private BigDecimal cap;

    public Money forCollection(Money amount) {
        BigDecimal variable = amount.amount()
                .multiply(BigDecimal.valueOf(basisPoints))
                .divide(BigDecimal.valueOf(10_000), scaleOf(amount), RoundingMode.HALF_UP);

        BigDecimal total = variable.add(fixed);
        if (cap != null && total.compareTo(cap) > 0) {
            total = cap;
        }
        // Normalise a l'echelle de la devise avant de construire le montant : sinon un
        // frais de 49,995 XAF ferait echouer Money, qui refuse tout arrondi implicite.
        total = total.setScale(scaleOf(amount), RoundingMode.HALF_UP);

        return Money.of(total, amount.currency());
    }

    private int scaleOf(Money amount) {
        return amount.currency().getDefaultFractionDigits();
    }

    public int getBasisPoints() {
        return basisPoints;
    }

    public void setBasisPoints(int basisPoints) {
        this.basisPoints = basisPoints;
    }

    public BigDecimal getFixed() {
        return fixed;
    }

    public void setFixed(BigDecimal fixed) {
        this.fixed = fixed;
    }

    public BigDecimal getCap() {
        return cap;
    }

    public void setCap(BigDecimal cap) {
        this.cap = cap;
    }
}
