package com.ocb.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Livre l'outbox en auto-configuration.
 *
 * <p>Le {@link OutboxWriter} est toujours disponible : c'est lui qui participe a la
 * transaction metier. Le {@link OutboxRelay}, en revanche, est conditionne — un test qui
 * verifie qu'un evenement reste en attente apres un echec Kafka doit pouvoir empecher un
 * relais d'arriere-plan de le publier entre deux assertions.
 */
@AutoConfiguration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(JdbcClient jdbcClient, OutboxProperties properties) {
        return new OutboxWriter(jdbcClient, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "ocb.outbox", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    public OutboxRelay outboxRelay(JdbcClient jdbcClient,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   OutboxProperties properties,
                                   PlatformTransactionManager transactionManager,
                                   MeterRegistry meterRegistry) {
        return new OutboxRelay(jdbcClient, kafkaTemplate, properties, transactionManager, meterRegistry);
    }

    /**
     * Filet : sans Actuator, aucun {@link MeterRegistry} n'est publie et le relais ne
     * pourrait pas etre construit. Les metriques seraient alors perdues sans que cela
     * soit visible.
     */
    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    public MeterRegistry outboxFallbackMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}
