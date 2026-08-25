package com.ocb.payment.application;

import com.ocb.platform.domain.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Frais de la plateforme sur un encaissement.
 *
 * <p>Le calcul lui-meme vit dans {@link FeeSchedule}, parce qu'il est le meme dans les
 * deux sens. Les taux, eux, sont distincts : encaisser et decaisser n'ont ni le meme cout
 * pour nous ni la meme valeur pour le client.
 */
@Component
@ConfigurationProperties(prefix = "ocb.fees.collection")
public class FeePolicy extends FeeSchedule {

    public Money forCollection(Money amount) {
        return compute(amount);
    }
}
