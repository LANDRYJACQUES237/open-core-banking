package com.ocb.provider.config;

import com.ocb.platform.events.Topics;
import com.ocb.provider.adapter.web.WebhookSignatureFilter;
import com.ocb.provider.application.RejectedCallbackRecorder;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Ce qui est propre a ce service.
 *
 * <p>La politique de retentative et de rebut vit desormais dans {@code common-kafka} :
 * elle etait identique a celle de payment-service, et une divergence dans le traitement
 * des echecs ne se voit qu'au moment ou elle coute quelque chose.
 */
@Configuration
public class ProviderConfig {

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
}
