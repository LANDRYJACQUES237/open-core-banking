package com.ocb.payment.application;

import com.ocb.platform.domain.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Frais de la plateforme sur un transfert.
 *
 * <p>Preleves sur l'emetteur, en plus du montant transfere : le destinataire recoit le
 * montant demande. Retenir les frais sur le montant recu obligerait l'emetteur a calculer
 * a l'envers pour qu'une somme ronde arrive a destination.
 */
@Component
@ConfigurationProperties(prefix = "ocb.fees.transfer")
public class TransferFeePolicy extends FeeSchedule {

    public Money forTransfer(Money amount) {
        return compute(amount);
    }
}
