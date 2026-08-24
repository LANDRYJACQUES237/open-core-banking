package com.ocb.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.OperationType;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.ProviderOperation;
import com.ocb.provider.domain.WebhookSignature;
import com.ocb.provider.domain.port.OperationStore;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * Socle des tests qui n'ont besoin ni de Kafka ni d'un operateur.
 *
 * <p>Les webhooks se pretent particulierement bien a cet isolement : recevoir un rappel
 * n'appelle personne. Le message est authentifie, conserve, l'operation avance et
 * l'evenement est depose dans l'outbox. Tout cela se verifie en base, sans bus.
 *
 * <p>C'est heureux, car c'est la partie la plus critique du service en matiere de
 * securite — celle qui est exposee a Internet — et elle est ainsi la plus facile a
 * exercer.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class ProviderPersistenceTestBase {

    protected static final String OWNER_USER = "provider_owner";
    protected static final String OWNER_PASSWORD = "owner-secret";
    protected static final String APP_USER = "provider_app";
    protected static final String APP_PASSWORD = "app-secret";

    protected static final String MTN_SECRET = "secret-de-test-mtn";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("provider")
                    .withUsername(OWNER_USER)
                    .withPassword(OWNER_PASSWORD)
                    .withInitScript("db/testcontainers-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("ocb.provider.webhook.secrets.MTN_MOMO", () -> MTN_SECRET);
        // ORANGE_MONEY reste volontairement sans secret : un test verifie qu'un rappel
        // adresse a un operateur non configure est refuse plutot que laisse passer.

        // Ni consommateur, ni relais, ni relance de statut : ces tests observent ce que la
        // reception d'un rappel laisse en base, pas ce qu'un traitement de fond en fait.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("ocb.outbox.enabled", () -> "false");
        registry.add("ocb.provider.poll.interval", () -> "PT1H");
        registry.add("ocb.provider.operators.base-urls.MTN_MOMO", () -> "http://operateur.invalid");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected OperationStore operations;

    protected String suffix;

    @BeforeEach
    void freshSuffix() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // --- Preparation -------------------------------------------------------------------

    /** Cree une operation en attente, comme si la commande avait deja ete traitee. */
    protected ProviderOperation givenPendingOperation(String externalRef, OperationStatus status) {
        UUID transactionId = UUID.randomUUID();
        Instant now = Instant.now();
        return operations.createOrGet(new ProviderOperation(
                UUID.randomUUID(), transactionId, ProviderCode.MTN_MOMO, OperationType.COLLECTION,
                externalRef, "collection:" + transactionId, "+237670000001",
                Money.parse("10000", "XAF"),
                status == OperationStatus.PENDING ? null : "MTN-REF-" + suffix,
                status, null, null, null, null,
                1, 0, null, now.plusSeconds(5), false, now, now, 0)).operation();
    }

    // --- Appels HTTP -------------------------------------------------------------------

    /** Envoie un rappel correctement signe. */
    protected ApiResponse postSignedCallback(String providerCode, String body) {
        long timestamp = Instant.now().getEpochSecond();
        return postCallback(providerCode, body,
                WebhookSignature.sign(MTN_SECRET, timestamp, body), String.valueOf(timestamp));
    }

    protected ApiResponse postCallback(String providerCode, String body,
                                       String signature, String timestamp) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .request(HttpMethod.POST, URI.create("/webhooks/" + providerCode))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (signature != null) {
            request.header("X-OCB-Signature", signature);
        }
        if (timestamp != null) {
            request.header("X-OCB-Timestamp", timestamp);
        }
        return execute(request);
    }

    protected ApiResponse get(String path) {
        return execute(MockMvcRequestBuilders.request(HttpMethod.GET, URI.create(path)));
    }

    private ApiResponse execute(MockHttpServletRequestBuilder request) {
        try {
            MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
            String payload = response.getContentAsString(StandardCharsets.UTF_8);
            JsonNode body = payload == null || payload.isBlank()
                    ? json.createObjectNode()
                    : json.readTree(payload);
            return new ApiResponse(response.getStatus(), body);
        } catch (Exception e) {
            throw new IllegalStateException("Appel HTTP simule en echec", e);
        }
    }

    // --- Observation -------------------------------------------------------------------

    protected String statusOf(UUID transactionId) {
        return jdbc.sql("""
                        SELECT status FROM provider.provider_operation WHERE transaction_id = :id
                        """)
                .param("id", transactionId)
                .query(String.class)
                .single();
    }

    protected long callbackCount(String providerEventId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM provider.provider_callback WHERE provider_event_id = :id
                        """)
                .param("id", providerEventId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    protected java.util.List<String> outboxEventTypes(UUID transactionId) {
        return jdbc.sql("""
                        SELECT event_type FROM provider.outbox_event
                         WHERE aggregate_id = :id ORDER BY seq
                        """)
                .param("id", transactionId.toString())
                .query(String.class)
                .list();
    }

    protected long auditCount(String action) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM provider.audit_log WHERE action = :action")
                .param("action", action)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    protected String callbackBody(String eventId, String externalRef, String status) {
        return """
                {"eventId":"%s","externalRef":"%s","providerRef":"MTN-REF-%s","status":"%s",\
                "fee":"150","currency":"XAF"}"""
                .formatted(eventId, externalRef, suffix, status);
    }

    public record ApiResponse(int status, JsonNode body) {

        public String code() {
            return body.has("code") ? body.get("code").asText() : null;
        }

        public boolean duplicate() {
            return body.has("duplicate") && body.get("duplicate").asBoolean();
        }
    }
}
