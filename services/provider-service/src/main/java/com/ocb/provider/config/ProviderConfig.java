package com.ocb.provider.config;

import com.ocb.platform.events.Topics;
import com.ocb.provider.adapter.web.WebhookSignatureFilter;
import com.ocb.provider.application.RejectedCallbackRecorder;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class ProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(ProviderConfig.class);

    /**
     * Le filtre de signature s'execute juste apres celui qui pose le correlationId, et
     * avant tout le reste. Une requete non authentifiee ne doit atteindre ni Jackson, ni
     * le controleur, ni la moindre logique metier.
     */
    @Bean
    public FilterRegistrationBean<WebhookSignatureFilter> webhookSignatureFilter(
            WebhookProperties properties, RejectedCallbackRecorder rejectedRecorder) {
        FilterRegistrationBean<WebhookSignatureFilter> registration =
                new FilterRegistrationBean<>(new WebhookSignatureFilter(properties, rejectedRecorder));
        registration.addUrlPatterns("/webhooks/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }

    @Bean
    public NewTopic providerCommandTopic() {
        return TopicBuilder.name(Topics.CMD_PROVIDER).partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic providerEventTopic() {
        return TopicBuilder.name(Topics.EVT_PROVIDER).partitions(6).replicas(1).build();
    }

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
        // Un message illisible ou invalide ne se resoudra jamais : le retenter bloquerait
        // la partition, donc toutes les transactions dont la cle atterrit dessus.
        handler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                IllegalArgumentException.class,
                com.ocb.platform.domain.error.InvariantViolationException.class);
        return handler;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<Object, Object> providerKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        // Via le configurateur, sans quoi tout le bloc spring.kafka.listener.* serait
        // ignore en silence.
        configurer.configure(factory, consumerFactory);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
