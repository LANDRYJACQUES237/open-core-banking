package com.ocb.platform.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ocb.outbox")
public class OutboxProperties {

    /**
     * Schema PostgreSQL contenant la table {@code outbox_event}.
     *
     * <p>Le module ne fournit pas la migration : chaque service possede son schema, et un
     * module partage qui creerait des tables dans la base d'un service romprait justement
     * la frontiere que le decoupage cherche a tenir.
     */
    private String schema = "public";

    /** Nombre d'evenements publies par transaction. */
    private int batchSize = 100;

    /** Intervalle entre deux cycles de publication. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /**
     * Delai maximal d'un envoi Kafka.
     *
     * <p>Volontairement court : le relais reessaiera au cycle suivant. Attendre longtemps
     * n'apporte rien puisque la ligne n'est jamais perdue, et bloquerait la publication de
     * tout ce qui suit.
     */
    private Duration sendTimeout = Duration.ofSeconds(10);

    /** Duree de conservation des evenements deja publies. */
    private Duration retention = Duration.ofDays(7);

    /** Intervalle de purge des evenements publies. */
    private Duration purgeInterval = Duration.ofHours(1);

    private boolean enabled = true;

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public Duration getPurgeInterval() {
        return purgeInterval;
    }

    public void setPurgeInterval(Duration purgeInterval) {
        this.purgeInterval = purgeInterval;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
