package com.ocb.provider.domain.port;

/**
 * Deduplication des messages Kafka consommes.
 *
 * <p>L'insertion a lieu dans la meme transaction que l'effet metier, et avant lui.
 */
public interface ProcessedMessageStore {

    boolean markProcessed(String consumerGroup, String eventId, String eventType);
}
