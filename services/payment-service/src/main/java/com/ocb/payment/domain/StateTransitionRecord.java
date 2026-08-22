package com.ocb.payment.domain;

import java.time.OffsetDateTime;

/**
 * Une tentative de changement d'etat, acceptee ou non.
 *
 * <p>Les refus sont conserves au meme titre que les acceptations. C'est ce qui rend la
 * machine a etats verifiable : la preuve qu'un callback duplique a ete neutralise est une
 * ligne en base, consultable par l'API, et non une ligne de log qui aura ete purgee le
 * jour ou on en aura besoin.
 */
public record StateTransitionRecord(
        long seq,
        TransactionStatus fromStatus,
        TransactionStatus toStatus,
        String triggerEvent,
        boolean accepted,
        String rejectionReason,
        OffsetDateTime occurredAt
) {
}
