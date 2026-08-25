package com.ocb.notification.config;

import com.ocb.platform.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Les topics dont ce service a besoin — un seul, en lecture.
 *
 * <p>La politique de retentative et de rebut n'est pas ici : elle vit dans
 * {@code common-kafka} et s'applique a ce service sans qu'il ait une ligne a ecrire. C'est
 * ce que l'extraction faite avant cette phase devait rendre possible, et ce service en est
 * la verification.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic paymentEventTopic() {
        return TopicBuilder.name(Topics.EVT_PAYMENT).partitions(6).replicas(1).build();
    }

    /**
     * Le topic de rebut est declare explicitement.
     *
     * <p>Sans cela il serait cree a la volee au premier message rejete — donc au pire
     * moment, et avec les reglages par defaut du courtier. Un message qui arrive en rebut
     * represente une transaction dont l'issue est inconnue : ce n'est pas le moment de
     * decouvrir qu'on ne peut pas l'y ecrire.
     */
    @Bean
    public NewTopic paymentEventDeadLetterTopic() {
        return TopicBuilder.name(Topics.deadLetter(Topics.EVT_PAYMENT))
                .partitions(6).replicas(1).build();
    }
}
