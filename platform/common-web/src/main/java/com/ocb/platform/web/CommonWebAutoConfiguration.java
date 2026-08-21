package com.ocb.platform.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

/**
 * Livre la couche web transverse en auto-configuration plutot qu'en composants a scanner.
 *
 * <p>Un service qui ajoute la dependance en beneficie sans elargir son {@code @ComponentScan}
 * a {@code com.ocb} — elargissement qui reviendrait a autoriser n'importe quel module
 * partage a injecter des beans dans n'importe quel service, exactement le glissement
 * vers le monolithe distribue que la regle de frontiere interdit.
 */
@AutoConfiguration
@ConditionalOnWebApplication
public class CommonWebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ProblemDetailsExceptionHandler problemDetailsExceptionHandler() {
        return new ProblemDetailsExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean(name = "correlationIdFilterRegistration")
    public FilterRegistrationBean<CorrelationIdFilter> correlationIdFilterRegistration() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.addUrlPatterns("/*");
        // Le plus tot possible : tout ce qui journalise ensuite doit disposer du correlationId,
        // y compris les erreurs levees par les filtres de securite ajoutes en Phase 5.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
