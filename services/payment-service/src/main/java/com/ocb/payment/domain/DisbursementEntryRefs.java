package com.ocb.payment.domain;

import java.util.UUID;

/**
 * References des ecritures d'une saga de decaissement.
 *
 * <p>Elles sont <b>derivees</b> de l'identifiant de transaction, jamais retrouvees. La
 * difference est importante pour une saga : l'etape qui compense doit savoir quelle
 * ecriture contre-passer, et elle s'execute potentiellement des heures plus tard, dans un
 * autre processus, apres une redelivraison de message. Une reference conservee en base
 * serait une chose de plus a ne pas perdre ; une reference calculee ne peut pas manquer.
 *
 * <p>Elles servent aussi de second garde-fou d'idempotence : le grand livre refuse deux
 * ecritures de meme reference. Meme si la cle d'idempotence etait mal transmise, une
 * livraison ne pourrait pas etre comptabilisee deux fois.
 */
public final class DisbursementEntryRefs {

    private DisbursementEntryRefs() {
    }

    /** Engagement des fonds : du portefeuille vers le compte de passage. */
    public static String reservation(UUID transactionId) {
        return "DISB-RES-" + transactionId;
    }

    /** Livraison : le compte de passage se solde vers le float de l'operateur. */
    public static String settlement(UUID transactionId) {
        return "DISB-SET-" + transactionId;
    }

    /** Compensation : contre-passation de l'engagement. */
    public static String reversal(UUID transactionId) {
        return "DISB-REV-" + transactionId;
    }
}
