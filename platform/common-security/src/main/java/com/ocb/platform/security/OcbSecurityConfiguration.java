package com.ocb.platform.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.security.oauth2.resource.OAuth2ResourceServerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

/**
 * Decodeur de jetons commun aux trois services.
 *
 * <p>Ce qui est partage : la maniere de <b>valider</b> un jeton — signature via JWKS,
 * emetteur, expiration, audience. Ce qui ne l'est pas : les regles d'<b>autorisation</b>.
 * Chaque service declare les siennes, parce qu'elles font partie de son contrat et non de
 * la plomberie. Les centraliser obligerait a redeployer un module partage pour ouvrir un
 * point de terminaison.
 */
@AutoConfiguration
@EnableConfigurationProperties(ResourceServerProperties.class)
public class OcbSecurityConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OcbSecurityConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder jwtDecoder(OAuth2ResourceServerProperties springProperties,
                                 ResourceServerProperties ocbProperties) {

        String issuer = springProperties.getJwt().getIssuerUri();
        String jwkSetUri = springProperties.getJwt().getJwkSetUri();

        if (issuer == null || issuer.isBlank()) {
            // L'emetteur attendu n'est pas une commodite : c'est lui qui distingue un jeton
            // emis par notre fournisseur d'identite d'un jeton emis par n'importe qui
            // d'autre. Sans lui, la validation ci-dessous n'aurait plus rien a comparer, et
            // le service accepterait des jetons signes par une autorite inconnue.
            throw new IllegalStateException(
                    "spring.security.oauth2.resourceserver.jwt.issuer-uri est obligatoire : "
                            + "sans emetteur attendu, aucun jeton ne peut etre rattache a une autorite");
        }

        // Avec jwk-set-uri, le decodeur ne contacte le fournisseur d'identite qu'au premier
        // jeton recu. Sans, withIssuerLocation va chercher le document de decouverte des la
        // construction du bean : un fournisseur indisponible empeche alors le service de
        // DEMARRER, et donc de redemarrer au moment precis ou on en a besoin. C'est la meme
        // regle que pour les sondes — la vivacite ne depend d'aucun systeme externe.
        //
        // La securite est identique dans les deux cas : la revendication iss est verifiee
        // par le validateur ci-dessous, jamais par la confiance accordee au document de
        // decouverte.
        NimbusJwtDecoder decoder = jwkSetUri != null && !jwkSetUri.isBlank()
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : NimbusJwtDecoder.withIssuerLocation(issuer).build();

        List<String> audiences = ocbProperties.getAudiences();
        if (audiences.isEmpty()) {
            // Signale plutot que de laisser croire. Un deploiement sans audience attendue
            // accepte tout jeton correctement signe par l'emetteur, y compris ceux emis
            // pour un autre service.
            log.warn("Aucune audience attendue configuree (ocb.security.audiences) : "
                    + "tout jeton signe par l'emetteur sera accepte, quel que soit son destinataire");
        }

        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer),
                new AudienceValidator(audiences));
        decoder.setJwtValidator(validator);
        return decoder;
    }
}
