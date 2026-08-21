package com.ocb.ledger.domain;

import java.util.Map;

/**
 * Entree du journal d'audit.
 *
 * <p>Le journal est en insertion seule. Il ne remplace pas le grand livre — celui-ci est
 * deja immuable — mais couvre ce que le grand livre ne raconte pas : l'ouverture d'un
 * compte, un gel, un arbitrage. Autrement dit, les actes qui ne produisent pas d'ecriture.
 *
 * <p>{@code payload} ne doit contenir aucune donnee personnelle : le grand livre n'en
 * detient pas, son journal d'audit ne doit pas en introduire par la bande.
 */
public record AuditEvent(
        String actorType,
        String actorId,
        String action,
        String resourceType,
        String resourceId,
        String correlationId,
        Map<String, Object> payload
) {

    public static AuditEvent of(String action, String resourceType, String resourceId,
                                String correlationId, Map<String, Object> payload) {
        // En Phase 1 le service n'est pas authentifie : l'acteur est le service appelant.
        // La securite arrive en Phase 5, et remplacera ces valeurs par le sujet du JWT.
        return new AuditEvent("SERVICE", "ledger-service", action, resourceType, resourceId,
                correlationId, payload);
    }
}
