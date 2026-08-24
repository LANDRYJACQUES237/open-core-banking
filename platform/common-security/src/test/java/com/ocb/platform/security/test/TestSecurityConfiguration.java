package com.ocb.platform.security.test;

import com.ocb.platform.security.AudienceValidator;
import com.ocb.platform.security.ResourceServerProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Decodeur de test, adosse a la cle publique de {@link TestJwtIssuer}.
 *
 * <p>Il remplace le decodeur de production — rendu {@code ConditionalOnMissingBean}
 * exactement pour cela — mais applique <b>les memes validations</b> : signature, emetteur,
 * audience, expiration. Seule la provenance de la cle change.
 *
 * <p>Ce n'est donc pas un contournement de la securite pour faire passer les tests. Un
 * jeton expire, mal signe, emis par un autre emetteur ou destine a un autre service est
 * refuse ici exactement comme il le serait en production. Ce qui n'est pas exerce, c'est
 * la recuperation de la cle par HTTP, qui releve de la configuration de deploiement.
 */
@TestConfiguration
public class TestSecurityConfiguration {

    @Bean
    public TestJwtIssuer testJwtIssuer() {
        return new TestJwtIssuer();
    }

    @Bean
    public JwtDecoder jwtDecoder(TestJwtIssuer issuer, ResourceServerProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(issuer.publicKey()).build();

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer.issuer()),
                new AudienceValidator(properties.getAudiences()));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
