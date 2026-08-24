package com.ocb.platform.security.test;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Emetteur de jetons pour les tests.
 *
 * <p>Forge de vrais JWT signes, que le vrai decodeur Spring valide vraiment : signature,
 * emetteur, audience, expiration. Ce n'est pas un raccourci du type « jeton deja
 * authentifie » — un tel outillage court-circuiterait le decodeur, et une erreur de
 * configuration d'emetteur ou d'audience passerait inapercue.
 *
 * <p>La cle publique est exposee directement plutot que servie par un point de terminaison
 * JWKS. La logique de validation exercee est identique ; seule la recuperation de la cle
 * differe, et elle releve de la configuration de deploiement, pas du code.
 *
 * <p>Cette classe est publiee en test-jar et partagee par les trois services. La dupliquer
 * garantirait qu'elle diverge, et un outillage de securite qui diverge de la production
 * ne prouve plus rien.
 */
public final class TestJwtIssuer {

    public static final String DEFAULT_ISSUER = "https://auth.test.ocb.dev/realms/ocb";

    private final RSAKey key;
    private final String issuer;

    public TestJwtIssuer() {
        this(DEFAULT_ISSUER);
    }

    public TestJwtIssuer(String issuer) {
        this.issuer = issuer;
        try {
            this.key = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (Exception e) {
            throw new IllegalStateException("Generation de cle impossible", e);
        }
    }

    public String issuer() {
        return issuer;
    }

    public RSAPublicKey publicKey() {
        try {
            return key.toRSAPublicKey();
        } catch (Exception e) {
            throw new IllegalStateException("Cle publique illisible", e);
        }
    }

    /** Jeton valide, emis maintenant, pour une heure. */
    public String token(String subject, String audience, String... scopes) {
        return token(subject, List.of(audience), Duration.ofHours(1), issuer, String.join(" ", scopes));
    }

    /** Jeton deja expire, pour verifier que l'expiration est reellement contrôlee. */
    public String expiredToken(String subject, String audience, String... scopes) {
        return token(subject, List.of(audience), Duration.ofMinutes(-30), issuer, String.join(" ", scopes));
    }

    /** Jeton emis par un autre emetteur, avec la meme cle : seul {@code iss} change. */
    public String tokenFromOtherIssuer(String subject, String audience, String... scopes) {
        return token(subject, List.of(audience), Duration.ofHours(1),
                "https://auth.attaquant.example/realms/ocb", String.join(" ", scopes));
    }

    /** Jeton pour un autre destinataire : signature et emetteur corrects, audience non. */
    public String tokenForOtherAudience(String subject, String... scopes) {
        return token(subject, List.of("un-autre-service"), Duration.ofHours(1), issuer,
                String.join(" ", scopes));
    }

    /** Jeton signe par une cle etrangere : la signature ne doit pas valider. */
    public String tokenSignedByAnotherKey(String subject, String audience, String... scopes) {
        TestJwtIssuer intruder = new TestJwtIssuer(issuer);
        return intruder.token(subject, audience, scopes);
    }

    private String token(String subject, List<String> audience, Duration validity,
                         String issuerClaim, String scope) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(issuerClaim)
                .audience(audience)
                .issueTime(Date.from(validity.isNegative() ? now.plus(validity) : now))
                .expirationTime(Date.from(validity.isNegative()
                        ? now.plus(validity).plus(Duration.ofMinutes(1))
                        : now.plus(validity)))
                .jwtID(UUID.randomUUID().toString())
                // La revendication "scope", separee par des espaces, est celle que Spring
                // convertit en autorisations prefixees SCOPE_.
                .claim("scope", scope)
                .build();

        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .type(JOSEObjectType.JWT)
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Signature du jeton impossible", e);
        }
    }
}
