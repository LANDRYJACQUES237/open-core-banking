package com.ocb.provider;

import com.ocb.platform.security.OcbScopes;
import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.ProviderOperation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les deux regimes d'authentification de ce service, et la frontiere entre eux.
 *
 * <p>C'est le seul service de la plateforme qui expose deux surfaces authentifiees
 * differemment : les webhooks par signature HMAC, le reste par jeton OIDC. La
 * configuration Spring les distingue par un simple {@code permitAll} sur
 * {@code /webhooks/**}, ce qui ressemble a s'y meprendre a une ouverture. Ces tests
 * verifient qu'il n'en est rien.
 */
class ProviderSecurityIT extends ProviderPersistenceTestBase {

    @Test
    @DisplayName("les webhooks n'exigent aucun jeton, mais exigent une signature")
    void webhooksNeedNoTokenButNeedASignature() {
        String externalRef = "TX-sec-" + suffix;
        ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

        // Aucun en-tete Authorization ici : un operateur Mobile Money ne dispose d'aucun
        // jeton emis par notre fournisseur d'identite, et n'en disposera jamais.
        ApiResponse signed = postSignedCallback("MTN_MOMO",
                callbackBody("evt-sec-" + suffix, externalRef, "SUCCEEDED"));
        assertThat(signed.status()).isEqualTo(200);
        assertThat(statusOf(operation.transactionId())).isEqualTo("SUCCEEDED");

        // Sans jeton non plus, mais sans signature valable : refuse. Le permitAll de la
        // chaine Spring signifie « authentifie autrement », pas « ouvert ».
        ApiResponse unsigned = postCallback("MTN_MOMO",
                callbackBody("evt-sec2-" + suffix, externalRef, "SUCCEEDED"), null, null);
        assertThat(unsigned.status()).isEqualTo(401);
    }

    @Test
    @DisplayName("un jeton valide ne remplace pas une signature sur un webhook")
    void aValidTokenDoesNotSubstituteForASignature() {
        // Le piege que ce test verrouille : quelqu'un disposant d'un jeton legitime — un
        // client interne, un service voisin — ne doit pas pouvoir injecter de faux rappels
        // d'operateur. Les deux mecanismes protegent des choses differentes et ne se
        // remplacent pas.
        String externalRef = "TX-sec3-" + suffix;
        ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .request(org.springframework.http.HttpMethod.POST,
                                java.net.URI.create("/webhooks/MTN_MOMO"))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(callbackBody("evt-sec3-" + suffix, externalRef, "SUCCEEDED"))
                        .header("Authorization", "Bearer " + defaultToken());

        try {
            int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
            assertThat(status).isEqualTo(401);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(statusOf(operation.transactionId()))
                .as("aucun faux rappel ne doit avoir fait avancer l'operation")
                .isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("le diagnostic exige un jeton, contrairement aux webhooks")
    void diagnosticsRequireAToken() {
        ProviderOperation operation =
                givenPendingOperation("TX-sec4-" + suffix, OperationStatus.ACCEPTED);

        assertThat(get("/v1/operations/" + operation.transactionId(), null).status())
                .as("l'etat d'une operation revele des montants et des references")
                .isEqualTo(401);

        assertThat(get("/v1/operations/" + operation.transactionId()).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("une portee d'un autre service ne donne pas acces au diagnostic")
    void foreignScopeGrantsNothing() {
        ProviderOperation operation =
                givenPendingOperation("TX-sec5-" + suffix, OperationStatus.ACCEPTED);

        String merchant = jwtIssuer.token("marchand", "provider-service",
                OcbScopes.PAYMENT_INITIATE, OcbScopes.LEDGER_READ);

        assertThat(get("/v1/operations/" + operation.transactionId(), merchant).status())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("un jeton emis pour un autre service est refuse")
    void wrongAudienceIsRejected() {
        ProviderOperation operation =
                givenPendingOperation("TX-sec6-" + suffix, OperationStatus.ACCEPTED);

        String elsewhere = jwtIssuer.tokenForOtherAudience("console", OcbScopes.PROVIDER_READ);

        assertThat(get("/v1/operations/" + operation.transactionId(), elsewhere).status())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("les sondes de sante restent accessibles sans jeton")
    void healthProbesStayOpen() {
        assertThat(get("/actuator/health/liveness", null).status()).isEqualTo(200);

        // Sur la readiness, l'assertion porte sur l'ACCES et non sur l'etat. Ce socle ne
        // demarre aucun courtier : la readiness inclut Kafka, elle repond donc 503, et
        // c'est le comportement correct. Un 503 prouve tout aussi bien que la chaine de
        // securite n'a pas rejete l'appel — ce que ce test verifie.
        assertThat(get("/actuator/health/readiness", null).status())
                .as("accessible sans jeton, quel que soit l'etat du courtier")
                .isNotIn(401, 403);
    }
}
