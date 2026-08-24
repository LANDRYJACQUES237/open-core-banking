package com.ocb.provider.domain.port;

import java.util.Map;

/**
 * Journal d'audit, en insertion seule.
 *
 * <p>Ce service etant la seule surface publique de la plateforme, il journalise aussi les
 * tentatives <b>refusees</b> : une signature invalide n'est pas un incident technique,
 * c'est un signal de securite qui doit rester consultable.
 */
public interface AuditStore {

    void append(String action, String resourceType, String resourceId,
                String correlationId, Map<String, Object> payload);
}
