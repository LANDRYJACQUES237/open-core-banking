package com.ocb.payment.config;

import com.ocb.platform.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean
    public NewTopic providerCommandTopic() {
        return TopicBuilder.name(Topics.CMD_PROVIDER).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic providerEventTopic() {
        return TopicBuilder.name(Topics.EVT_PROVIDER).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventTopic() {
        return TopicBuilder.name(Topics.EVT_PAYMENT).partitions(6).replicas(1).build();
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
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template) {
        ExponentialBackOff backOff = new ExponentialBackOff(500L, 2.0);
        backOff.setMaxAttempts(5);
        backOff.setMaxInterval(10_000L);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (record, exception) -> {
                    log.error("Message envoye en rebut depuis {} : {}",
                            record.topic(), exception.getMessage());
                    return new org.apache.kafka.common.TopicPartition(
                            Topics.deadLetter(record.topic()), record.partition());
                });

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class,
                com.ocb.platform.domain.error.InvariantViolationException.class);
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
    public ConcurrentKafkaListenerContainerFactory<Object, Object> paymentKafkaListenerContainerFactory(
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
