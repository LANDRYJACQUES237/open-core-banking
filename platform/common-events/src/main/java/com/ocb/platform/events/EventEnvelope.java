package com.ocb.platform.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Enveloppe commune a tout message publie sur Kafka.
 *
 * <p>Trois champs meritent d'etre compris avant d'ecrire un producteur ou un consommateur.
 *
 * <p><b>{@code eventId}</b> est la cle de deduplication. Kafka garantit une livraison
 * <i>au moins une fois</i> : un consommateur qui redemarre entre le traitement d'un message
 * et la validation de son offset le recevra a nouveau. Sans identifiant stable porte par le
 * message lui-meme, il n'aurait aucun moyen de distinguer ce doublon d'un nouvel evenement.
 * Il doit rester identique lors d'une republication — d'ou le fait qu'il soit attribue a
 * l'ecriture dans l'outbox, et non au moment de la publication.
 *
 * <p><b>{@code correlationId}</b> traverse le flux entier, du premier appel REST a la
 * notification finale. C'est ce qui permet de suivre une transaction a travers quatre
 * services et un bus de messages.
 *
 * <p><b>{@code causationId}</b> designe l'evenement ou la commande qui a cause celui-ci.
 * Il permet de reconstruire l'arbre causal d'une transaction, ce qu'un horodatage ne
 * permet pas des que plusieurs evenements partagent la meme milliseconde.
 *
 * @param payload charge utile, dont la structure depend de {@code eventType} et est
 *                decrite dans {@code contracts/events/payloads.schema.json}
 */
public record EventEnvelope(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String causationId,
        String producer,
        Object payload
) {

    public static final int CURRENT_VERSION = 1;

    public static EventEnvelope of(String eventType,
                                   String aggregateType,
                                   String aggregateId,
                                   String correlationId,
                                   String causationId,
                                   String producer,
                                   Object payload) {
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                CURRENT_VERSION,
                Instant.now(),
                aggregateType,
                aggregateId,
                correlationId,
                causationId,
                producer,
                payload);
    }
}
