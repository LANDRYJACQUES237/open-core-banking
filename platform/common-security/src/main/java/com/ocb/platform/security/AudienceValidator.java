package com.ocb.platform.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Verifie que le jeton a bien ete emis pour ce service.
 *
 * <p>Sans cette verification, la securite se resumerait a « la signature est bonne ». Or
 * un jeton signe par le meme emetteur mais destine a un autre service — une console
 * d'administration, un outil de reporting — passerait alors avec l'integralite de ses
 * portees. Un service compromis pourrait rejouer les jetons qu'il recoit contre ses
 * voisins.
 *
 * <p>L'audience repond a la question que la signature ne pose pas : <b>pour qui</b> ce
 * jeton a-t-il ete emis.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token",
            "Le jeton n'a pas ete emis pour ce service",
            null);

    private final List<String> expected;

    public AudienceValidator(List<String> expected) {
        this.expected = List.copyOf(expected);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (expected.isEmpty()) {
            // Aucune audience configuree : on n'invente pas une verification qui
            // laisserait croire a une protection inexistante. C'est un choix de
            // deploiement, signale au demarrage par OcbSecurityConfiguration.
            return OAuth2TokenValidatorResult.success();
        }
        List<String> actual = token.getAudience();
        return actual != null && actual.stream().anyMatch(expected::contains)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(ERROR);
    }
}
