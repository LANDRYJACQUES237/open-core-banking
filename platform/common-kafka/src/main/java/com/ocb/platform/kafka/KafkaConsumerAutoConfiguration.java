package com.ocb.platform.kafka;

import com.ocb.platform.domain.error.InvariantViolationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.JdbcClientAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

import com.ocb.platform.events.Topics;

/**
 * Ce que tout consommateur de la plateforme fait de la meme facon.
 *
 * <p>Cette politique etait ecrite a l'identique dans deux services et allait l'etre dans
 * un troisieme. La dupliquer n'est pas seulement redondant : elle finit par diverger, et
 * une divergence dans le traitement des echecs ne se voit qu'au moment ou elle coute
 * quelque chose.
 *
 * <p>Ce que ce module ne contient pas, et ne doit jamais contenir : quels topics existent,
 * quels evenements ils portent, ce qu'un service en fait. Cela appartient a chaque
 * service. Ici, il n'y a que la maniere d'echouer.
 */
@AutoConfiguration(after = {KafkaAutoConfiguration.class, JdbcClientAutoConfiguration.class})
@ConditionalOnClass({KafkaTemplate.class, JdbcClient.class})
@EnableConfigurationProperties(KafkaConsumerProperties.class)
public class KafkaConsumerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(JdbcClient.class)
    public ProcessedMessageStore processedMessageStore(JdbcClient jdbcClient,
                                                       KafkaConsumerProperties properties) {
        return new JdbcProcessedMessageStore(jdbcClient, properties);
    }

    /**
     * Gestion des echecs de consommation.
     *
     * <p>Deux categories a ne pas traiter pareil.
     *
     * <p>Une erreur <b>transitoire</b> — grand livre injoignable, base momentanement
     * indisponible — merite d'etre retentee : le backoff exponentiel laisse le temps au
     * systeme en aval de revenir.
     *
     * <p>Une erreur <b>definitive</b> — message illisible, invariant viole — ne se
     * resoudra jamais. La retenter bloquerait la partition indefiniment : tous les
     * messages suivants portant la meme cle, donc toutes les etapes suivantes des memes
     * transactions, attendraient derriere elle. Ces messages partent immediatement en
     * rebut.
     *
     * <p>Le rebut n'est pas une poubelle : c'est une file qu'un humain doit examiner.
     * Un message qui y arrive represente une transaction dont l'issue est inconnue.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorHandler ocbKafkaErrorHandler(KafkaTemplate<String, String> template,
                                                    KafkaConsumerProperties properties) {
        KafkaConsumerProperties.Retry retry = properties.getRetry();

        ExponentialBackOff backOff = new ExponentialBackOff(
                retry.getInitialInterval().toMillis(), retry.getMultiplier());
        backOff.setMaxAttempts(retry.getMaxAttempts());
        backOff.setMaxInterval(retry.getMaxInterval().toMillis());

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> {
                    log.error("Message envoye en rebut depuis {} : {}",
                            record.topic(), exception.getMessage());
                    // Meme partition que l'original : les messages d'une meme transaction
                    // restent ordonnes entre eux jusque dans le rebut, ce qui rend
                    // l'examen humain lisible.
                    return new TopicPartition(
                            Topics.deadLetter(record.topic()), record.partition());
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                JsonProcessingException.class,
                IllegalArgumentException.class,
                InvariantViolationException.class);
        return handler;
    }

    /**
     * Fabrique de conteneurs de consommation.
     *
     * <p>Construite via le configurateur de Spring Boot plutot qu'a la main. La nuance
     * n'est pas cosmetique : une fabrique instanciee directement ignore tout le bloc
     * {@code spring.kafka.listener.*} — mode de validation des offsets, concurrence,
     * demarrage automatique. On croirait alors piloter le comportement par configuration
     * alors qu'on utiliserait des valeurs par defaut, ce qui est le genre d'ecart qui ne
     * se voit qu'en production.
     */
    @Bean
    @ConditionalOnMissingBean(name = "ocbKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<Object, Object> ocbKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
