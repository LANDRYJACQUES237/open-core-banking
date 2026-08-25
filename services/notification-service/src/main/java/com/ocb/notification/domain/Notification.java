package com.ocb.notification.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Un message emis.
 *
 * @param recipientRef reference du portefeuille, jamais un numero de telephone. Aucun
 *                     service ne conserve le numero en clair : payment-service n'en garde
 *                     que la forme masquee, qui ne permet de joindre personne. Resoudre
 *                     cette reference en canal joignable appartient a l'adaptateur d'envoi
 * @param message      texte destine a etre lu tel quel. Il ne contient ni identifiant
 *                     technique ni numero : ce qui n'a pas de sens pour le destinataire
 *                     n'a rien a y faire
 */
public record Notification(UUID id,
                           UUID transactionId,
                           NotificationType type,
                           NotificationChannel channel,
                           String recipientRef,
                           String message,
                           String correlationId,
                           OffsetDateTime deliveredAt) {
}
