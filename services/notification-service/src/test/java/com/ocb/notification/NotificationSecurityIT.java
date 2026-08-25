package com.ocb.notification;

import com.ocb.platform.security.OcbScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui peut savoir ce qui a ete dit a un client.
 *
 * <p>La surface est reduite — une seule lecture — mais elle n'est pas anodine : la liste
 * des messages envoyes a un portefeuille renseigne sur son activite, y compris sans
 * montant. Savoir qu'un compte a recu quatre notifications d'encaissement dans la journee
 * en dit deja beaucoup.
 */
class NotificationSecurityIT extends NotificationTestBase {

    @Test
    @DisplayName("sans jeton, rien n'est consultable")
    void noTokenIsRejected() {
        assertThat(get("/v1/notifications/" + UUID.randomUUID(), null).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("un jeton signe par une autre cle est refuse")
    void foreignSignatureIsRejected() {
        String forged = jwtIssuer.tokenSignedByAnotherKey(
                "exploitation", "notification-service", OcbScopes.NOTIFICATION_READ);

        assertThat(get("/v1/notifications/" + UUID.randomUUID(), forged).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("un jeton emis pour un autre service est refuse")
    void wrongAudienceIsRejected() {
        String elsewhere = jwtIssuer.tokenForOtherAudience("console", OcbScopes.NOTIFICATION_READ);

        assertThat(get("/v1/notifications/" + UUID.randomUUID(), elsewhere).status()).isEqualTo(401);
    }

    @Test
    @DisplayName("une portee d'un autre service ne donne rien ici")
    void foreignScopeGrantsNothing() {
        String merchant = jwtIssuer.token("marchand", "notification-service",
                OcbScopes.PAYMENT_INITIATE, OcbScopes.PAYMENT_READ);

        assertThat(get("/v1/notifications/" + UUID.randomUUID(), merchant).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("les sondes de sante restent accessibles sans jeton")
    void healthProbesStayOpen() {
        assertThat(get("/actuator/health/liveness", null).status()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("les metriques ne sont pas ouvertes")
    void metricsAreNotOpen() {
        assertThat(get("/actuator/metrics", null).status()).isIn(401, 403, 404);
    }
}
