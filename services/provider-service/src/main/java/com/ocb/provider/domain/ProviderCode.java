package com.ocb.provider.domain;

/**
 * Operateurs supportes.
 *
 * <p>Enumeration propre a ce service, deliberement distincte de celle de
 * {@code payment-service} malgre des valeurs identiques. Les partager via un module
 * commun creerait un couplage : ajouter un operateur ici forcerait a redeployer le
 * moteur de paiement. Ce qui lie les deux services est le contrat d'evenements, ou le
 * code circule sous forme de chaine.
 */
public enum ProviderCode {
    MTN_MOMO,
    ORANGE_MONEY
}
