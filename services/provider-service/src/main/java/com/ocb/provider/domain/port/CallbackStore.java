package com.ocb.provider.domain.port;

import com.ocb.provider.domain.ProviderCode;

import java.util.UUID;

/**
 * Conservation des rappels entrants.
 *
 * <p>Le message brut est enregistre <b>avant</b> tout traitement metier, y compris quand
 * sa signature est invalide. Deux raisons : pouvoir reconstituer ce que l'operateur a
 * reellement envoye lors d'un litige, et garder trace des tentatives non authentifiees,
 * qui sont un signal de securite.
 */
public interface CallbackStore {

    /**
     * @return faux si ce rappel avait deja ete recu. Les operateurs rejouent, parfois
     * des heures plus tard : c'est ici que le doublon est reconnu, avant d'atteindre la
     * logique metier
     */
    boolean record(ProviderCode providerCode,
                   String providerEventId,
                   String externalRef,
                   UUID transactionId,
                   String signature,
                   boolean signatureValid,
                   String rawPayload);

    void markProcessed(ProviderCode providerCode, String providerEventId);
}
