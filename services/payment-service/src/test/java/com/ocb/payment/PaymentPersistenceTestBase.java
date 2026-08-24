package com.ocb.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

/**
 * Socle des tests qui n'ont pas besoin de Kafka.
 *
 * <p>La separation est deliberee. L'idempotence sous concurrence et l'atomicite de
 * l'outbox sont des proprietes de la <b>base de donnees</b> : les verifier a travers un
 * bus ajouterait de l'asynchronisme, donc des attentes et de l'intermittence, sans rien
 * prouver de plus. Le flux complet, lui, a bien besoin de Kafka et vit dans
 * {@code PaymentKafkaTestBase}.
 *
 * <p>Les consommateurs et le relais sont donc desactives ici : rien ne consomme, rien ne
 * publie, et l'etat observe apres un appel HTTP est exactement celui que la transaction a
 * laisse.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(
        com.ocb.platform.security.test.TestSecurityConfiguration.class)
public abstract class PaymentPersistenceTestBase {

    protected static final String OWNER_USER = "payment_owner";
    protected static final String OWNER_PASSWORD = "owner-secret";
    protected static final String APP_USER = "payment_app";
    protected static final String APP_PASSWORD = "app-secret";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payment")
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

        // Aucun faux serveur de grand livre ici, et ce n'est pas un oubli : une demande
        // d'encaissement n'appelle jamais le grand livre. L'ecriture comptable n'a lieu
        // qu'a la confirmation de l'operateur, donc dans le flux complet. Demarrer un
        // serveur que le test n'appelle jamais ajouterait une dependance sans contrepartie.
        registry.add("ocb.ledger.base-url", () -> "http://ledger.invalid");

        // Ni consommateur ni relais : ces tests observent ce que la transaction a ecrit,
        // pas ce qu'un traitement de fond en a fait ensuite.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("ocb.outbox.enabled", () -> "false");
        registry.add("ocb.provider.simulator.enabled", () -> "false");

        // Desactive la declaration des topics au demarrage.
        //
        // Sans cela, KafkaAdmin tente de joindre un courtier des l'initialisation du
        // contexte pour creer les topics declares par KafkaConfig. Aucun courtier ne
        // tourne ici — et c'est normal, ces tests n'en ont pas besoin — mais l'attente
        // dure trente secondes par contexte et laisse une pile d'erreurs dans les
        // journaux, ce qui masque les vraies causes d'echec.
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        // Le grand livre n'est jamais appele ici, donc aucun jeton de service n'est
        // demande. Le secret est neanmoins renseigne pour que la configuration du client
        // soit complete au demarrage.
        registry.add("spring.security.oauth2.client.registration.ledger.client-secret",
                () -> "secret-de-test");
        registry.add("spring.security.oauth2.client.provider.ocb.token-uri",
                () -> "http://auth.invalid/token");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected com.ocb.platform.security.test.TestJwtIssuer jwtIssuer;

    protected String suffix;

    @BeforeEach
    void freshSuffix() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // --- Appels HTTP ------------------------------------------------------------------

    /** Jeton par defaut : les deux portees du moteur de paiement. */
    protected String defaultToken() {
        return jwtIssuer.token("marchand", "payment-service",
                com.ocb.platform.security.OcbScopes.PAYMENT_INITIATE,
                com.ocb.platform.security.OcbScopes.PAYMENT_READ);
    }

    protected ApiResponse post(String path, String idempotencyKey, String body) {
        return post(path, idempotencyKey, body, defaultToken());
    }

    protected ApiResponse post(String path, String idempotencyKey, String body, String token) {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(HttpMethod.POST, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return execute(request);
    }

    protected ApiResponse get(String path) {
        return get(path, defaultToken());
    }

    protected ApiResponse get(String path, String token) {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(HttpMethod.GET, URI.create(path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return execute(request);
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

    // --- Raccourcis metier ------------------------------------------------------------

    protected String collectionBody(String amount) {
        return collectionBody(amount, "+237670000001");
    }

    protected String collectionBody(String amount, String msisdn) {
        return """
                {
                  "externalRef": "TX-%s",
                  "amount": "%s",
                  "currency": "XAF",
                  "payerMsisdn": "%s",
                  "walletAccountRef": "2100.wallet-%s",
                  "providerCode": "MTN_MOMO"
                }
                """.formatted(suffix, amount, msisdn, suffix);
    }

    /**
     * Compte les evenements d'une transaction precise.
     *
     * <p>Le conteneur PostgreSQL est partage par toute la classe et les ecritures ne sont
     * pas nettoyees entre deux tests. Compter globalement rendrait donc les assertions
     * dependantes de l'ordre d'execution — le genre d'echec intermittent qui coute des
     * heures. Chaque test raisonne sur son propre agregat.
     */
    protected long outboxCount(String transactionId, String eventType) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM payment.outbox_event
                         WHERE aggregate_id = :id AND event_type = :type
                        """)
                .param("id", transactionId)
                .param("type", eventType)
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    /**
     * Total tous agregats confondus.
     *
     * <p>A n'utiliser qu'en variation avant/apres, jamais en valeur absolue : la base est
     * partagee par la classe de test.
     */
    protected long totalOutboxCount(String eventType) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM payment.outbox_event WHERE event_type = :type")
                .param("type", eventType).query(Long.class).single();
        return count == null ? 0 : count;
    }

    protected long transactionCount() {
        Long count = jdbc.sql("SELECT COUNT(*) FROM payment.payment_transaction")
                .query(Long.class).single();
        return count == null ? 0 : count;
    }

    public record ApiResponse(int status, JsonNode body) {

        public String code() {
            return body.has("code") ? body.get("code").asText() : null;
        }

        public String transactionId() {
            return body.get("transactionId").asText();
        }

        public String status_() {
            return body.get("status").asText();
        }
    }
}
