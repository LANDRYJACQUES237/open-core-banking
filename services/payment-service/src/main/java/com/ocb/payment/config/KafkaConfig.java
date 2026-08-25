package com.ocb.payment.config;

import com.ocb.platform.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Les topics dont ce service a besoin.
 *
 * <p>Ce qui n'est plus ici : la politique de retentative et de rebut, et la fabrique de
 * conteneurs de consommation. Elles etaient identiques mot pour mot dans deux services et
 * vivent desormais dans {@code common-kafka}. Ne reste ici que ce qui est propre au
 * service — quels topics il produit et consomme, ce qu'aucun module partage ne peut
 * savoir a sa place.
 */
@Configuration
public class KafkaConfig {

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
}
