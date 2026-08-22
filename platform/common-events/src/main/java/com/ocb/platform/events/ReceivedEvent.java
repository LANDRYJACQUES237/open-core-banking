package com.ocb.platform.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;

/**
 * Enveloppe telle qu'elle arrive du bus.
 *
 * <p>Distincte de {@link EventEnvelope} sur un point : la charge utile reste un
 * {@link JsonNode} tant que le consommateur n'a pas decide de quel type il s'agit.
 *
 * <p>Ce n'est pas de la paresse. Un topic porte plusieurs types d'evenements — c'est ce
 * qui preserve leur ordre relatif pour un meme agregat. Un consommateur doit donc pouvoir
 * lire {@code eventType} <b>avant</b> de tenter une desserialisation, et surtout pouvoir
 * ignorer proprement un type qu'il ne connait pas. Deserialiser en dur vers une classe
 * ferait echouer le consommateur des qu'un nouveau type apparait sur le topic, ce qui
 * transformerait un ajout retrocompatible en panne.
 */
public record ReceivedEvent(
        String eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String causationId,
        String producer,
        JsonNode payload
) {

    public <T> T payloadAs(ObjectMapper mapper, Class<T> type) {
        return mapper.convertValue(payload, type);
    }
}
