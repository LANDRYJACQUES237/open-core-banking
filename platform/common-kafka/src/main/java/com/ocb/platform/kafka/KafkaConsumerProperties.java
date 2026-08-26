package com.ocb.platform.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ocb.kafka.consumer")
public class KafkaConsumerProperties {

    /**
     * Schema PostgreSQL contenant la table {@code processed_message}.
     *
     * <p>Le module ne fournit pas la migration. Chaque service possede son schema, et un
     * module partage qui creerait des tables dans la base d'un service romprait la
     * frontiere que le decoupage cherche a tenir. C'est le meme arbitrage que pour
     * l'outbox.
     */
    private String schema = "public";

    private final Retry retry = new Retry();

    private final Health health = new Health();

    public String getSchema() {
        return schema;
    }

    public void setSchema(String schema) {
        this.schema = schema;
    }

    public Retry getRetry() {
        return retry;
    }

    public Health getHealth() {
        return health;
    }

    /** Sonde de disponibilite du courtier. */
    public static class Health {

        /**
         * Delai maximal d'interrogation du courtier.
         *
         * <p>Court, et c'est essentiel : une sonde de disponibilite est appelee toutes les
         * quelques secondes. Sans borne, un courtier qui accepte la connexion sans repondre
         * immobiliserait un fil de requete a chaque appel jusqu'a epuisement du pool, et le
         * service tomberait pour une raison sans rapport avec la question posee.
         */
        private Duration timeout = Duration.ofSeconds(2);

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }
    }

    /**
     * Retentative avant mise au rebut.
     *
     * <p>Ces valeurs ne concernent que les erreurs <b>transitoires</b>. Une erreur
     * definitive part au rebut immediatement, sans consommer la moindre tentative.
     */
    public static class Retry {

        /** Delai avant la premiere retentative. */
        private Duration initialInterval = Duration.ofMillis(500);

        /** Facteur de croissance entre deux tentatives. */
        private double multiplier = 2.0;

        /**
         * Plafond du delai entre deux tentatives.
         *
         * <p>Sans plafond, la croissance exponentielle immobiliserait la partition
         * pendant des minutes sur un incident qui dure.
         */
        private Duration maxInterval = Duration.ofSeconds(10);

        /**
         * Nombre total de tentatives, la premiere comprise.
         *
         * <p>Fini, volontairement. Retenter indefiniment ne rend pas le systeme plus
         * robuste : cela remplace un message en rebut — visible, denombrable — par une
         * partition bloquee dont personne ne verra la cause.
         */
        private int maxAttempts = 5;

        public Duration getInitialInterval() {
            return initialInterval;
        }

        public void setInitialInterval(Duration initialInterval) {
            this.initialInterval = initialInterval;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public Duration getMaxInterval() {
            return maxInterval;
        }

        public void setMaxInterval(Duration maxInterval) {
            this.maxInterval = maxInterval;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }
    }
}
