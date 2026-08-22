package com.ocb.payment.domain.port;

import java.util.Map;

/**
 * Journal d'audit, en insertion seule.
 *
 * <p>Il ne fait pas doublon avec {@code transaction_state_transition}, qui raconte le
 * cycle de vie d'une transaction. Celui-ci enregistre les actes : qui a demande quoi,
 * quand, avec quelle correlation. Les deux repondent a des questions differentes lors
 * d'un incident.
 *
 * <p>Le chainage de hachage implemente dans le grand livre n'est pas reproduit ici : il
 * sera extrait dans un module partage quand un troisieme service en aura besoin. Le
 * dupliquer maintenant figerait une implementation qui n'a servi qu'une fois.
 */
public interface AuditStore {

    void append(String action, String resourceType, String resourceId,
                String correlationId, Map<String, Object> payload);
}
