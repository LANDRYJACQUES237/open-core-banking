package com.ocb.payment.application;

import com.ocb.platform.domain.money.Money;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Frais de la plateforme sur un decaissement.
 *
 * <p>Ces frais sont preleves <b>en plus</b> du montant envoye : le client qui decaisse
 * 5 000 voit son portefeuille debite de 5 050. C'est l'inverse de l'encaissement, ou les
 * frais sont retenus <b>sur</b> le montant recu.
 *
 * <p>La consequence compte pour le controle de solde : il faut verifier le portefeuille
 * contre le montant augmente des frais, pas contre le seul montant. Verifier le montant
 * seul laisserait passer un decaissement finançable a un franc pres et mettrait le
 * portefeuille a decouvert du montant des frais.
 */
@Component
@ConfigurationProperties(prefix = "ocb.fees.disbursement")
public class DisbursementFeePolicy extends FeeSchedule {

    public Money forDisbursement(Money amount) {
        return compute(amount);
    }
}
