package com.ocb.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
// Importee en tant que classe et non en imports statiques : WireMock.post entrerait sinon
// en collision avec l'aide HTTP post(...) de ce socle, et le compilateur choisirait la
// mauvaise sans que la cause soit evidente a la lecture.
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

/**
 * Socle des tests qui traversent reellement Kafka.
 *
 * <p>Trois conteneurs partages par la classe : PostgreSQL, Kafka, et un faux grand livre
 * servi par WireMock. Le service tourne au complet — relais d'outbox, consommateur
 * d'evenements operateur et operateur simule inclus.
 *
 * <p><b>Contrainte a respecter si une seconde classe de test Kafka apparait.</b> Elle doit
 * heriter de ce socle <i>sans redefinir la moindre propriete</i>. Un
 * {@code @TestPropertySource} sur une sous-classe ferait construire a Spring un second
 * contexte, et les deux resteraient vivants simultanement dans le cache : deux jeux de
 * consommateurs liraient alors les memes topics en ecrivant dans la meme base. Le partage
 * du contexte est ici une condition de correction, pas une optimisation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(
        com.ocb.platform.security.test.TestSecurityConfiguration.class)
public abstract class PaymentKafkaTestBase {

    protected static final String OWNER_USER = "payment_owner";
    protected static final String OWNER_PASSWORD = "owner-secret";
    protected static final String APP_USER = "payment_app";
    protected static final String APP_PASSWORD = "app-secret";

    protected static final String LEDGER_PATH = "/v1/journal-entries";

    /** Delai maximal d'attente d'un etat observable. Genereux : un runner charge est lent. */
    protected static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(45);

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("payment")
                    .withUsername(OWNER_USER)
                    .withPassword(OWNER_PASSWORD)
                    .withInitScript("db/testcontainers-init.sql");

    /**
     * Courtier Kafka en mode KRaft.
     *
     * <p><b>La version est epinglee en 3.8, et ce n'est pas un caprice.</b> Testcontainers
     * demarre le conteneur avec {@code KAFKA_LISTENERS} pointant sur {@code 0.0.0.0} et ne
     * renseigne les listeners annonces qu'ensuite, via un script, une fois le port publie
     * connu. L'entrypoint d'{@code apache/kafka:3.9} valide la configuration complete
     * <i>avant</i> cela, au moment du formatage du stockage, et refuse
     * {@code 0.0.0.0} comme adresse annoncee : le conteneur sort en erreur avant meme
     * d'ouvrir un port. La 3.8 ne fait pas cette validation au formatage.
     *
     * <p>Le client Kafka reste en 3.9 : un client recent parle sans probleme a un courtier
     * plus ancien, la negociation de version d'API s'en charge.
     */
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    protected static final WireMockServer LEDGER = new WireMockServer(options().dynamicPort());

    /**
     * Suffixe unique par execution de JVM applique aux groupes de consommation.
     *
     * <p>Sans lui, une seconde execution reutilisant un courtier deja peuple reprendrait
     * aux offsets valides precedemment et n'observerait jamais les messages attendus.
     */
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    static {
        POSTGRES.start();
        KAFKA.start();
        LEDGER.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("ocb.kafka.groups.provider-events", () -> "payment-events-" + RUN_ID);
        registry.add("ocb.kafka.groups.provider-simulator", () -> "provider-sim-" + RUN_ID);

        registry.add("ocb.ledger.base-url", LEDGER::baseUrl);

        // Relais rapide : le chemin reel est exerce, relais compris, et les attentes
        // restent breves. Piloter le relais a la main donnerait plus de controle mais
        // testerait moins.
        registry.add("ocb.outbox.poll-interval", () -> "PT0.25S");

        // Le point de terminaison de jetons est joue par le meme WireMock que le grand
        // livre : le flux client_credentials est donc reellement exerce, pas court-circuite.
        registry.add("spring.security.oauth2.client.registration.ledger.client-secret",
                () -> "secret-de-test");
        registry.add("spring.security.oauth2.client.provider.ocb.token-uri",
                () -> LEDGER.baseUrl() + "/oauth2/token");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected KafkaTemplate<String, String> kafka;

    @Autowired
    protected com.ocb.platform.security.test.TestJwtIssuer jwtIssuer;

    protected String suffix;

    @BeforeEach
    void resetStubsAndSuffix() {
        // Remet a zero les bouchons ET le journal des requetes : chaque test compte les
        // appels au grand livre pour lui-meme.
        LEDGER.resetAll();
        stubTokenEndpoint();
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Point de terminaison de jetons, joue par le meme serveur que le grand livre.
     *
     * <p>Le flux client_credentials est ainsi reellement traverse : si l'intercepteur
     * cessait d'attacher le jeton, ou si la configuration du client etait fausse, aucun
     * appel n'atteindrait le grand livre et les tests de flux echoueraient.
     */
    protected void stubTokenEndpoint() {
        LEDGER.stubFor(WireMock.post(urlEqualTo("/oauth2/token")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"access_token\":\"jeton-de-service\",\"token_type\":\"Bearer\","
                        + "\"expires_in\":3600}")));
    }

    // --- Bouchons du grand livre ------------------------------------------------------

    protected void stubLedgerAccepts() {
        LEDGER.stubFor(WireMock.post(urlEqualTo(LEDGER_PATH)).willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"entryRef\":\"JE-STUB-" + suffix + "\"}")));
    }

    /**
     * Nombre d'appels au grand livre <b>pour cette transaction</b>.
     *
     * <p>Le filtrage par cle d'idempotence est indispensable : WireMock est partage par la
     * classe, et un comptage global rendrait les assertions dependantes de l'ordre
     * d'execution des tests.
     */
    protected int ledgerCallsFor(String transactionId) {
        return LEDGER.findAll(postRequestedFor(urlEqualTo(LEDGER_PATH))
                .withHeader("Idempotency-Key", equalTo("collection:" + transactionId))).size();
    }

    protected List<JsonNode> ledgerRequestBodiesFor(String transactionId) {
        return LEDGER.findAll(postRequestedFor(urlEqualTo(LEDGER_PATH))
                        .withHeader("Idempotency-Key", equalTo("collection:" + transactionId)))
                .stream()
                .map(request -> {
                    try {
                        return json.readTree(request.getBodyAsString());
                    } catch (Exception e) {
                        throw new IllegalStateException("Corps de requete illisible", e);
                    }
                })
                .toList();
    }

    // --- Attente d'un etat observable -------------------------------------------------

    /**
     * Attend qu'une transaction atteigne un statut, sans jamais dormir.
     *
     * <p>En cas d'echec, le message porte l'etat complet — statut, historique des
     * transitions, contenu de l'outbox. Un simple delai depasse dirait seulement
     * "pas arrive" ; ce diagnostic dit <i>ou</i> la chaine s'est arretee.
     */
    protected void awaitStatus(String transactionId, String expected) {
        await().atMost(SETTLE_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> org.assertj.core.api.Assertions
                        .assertThat(statusOf(transactionId))
                        .as("%s", diagnostics(transactionId))
                        .isEqualTo(expected));
    }

    /**
     * Attend que le relais ait publie tous les evenements d'une transaction.
     *
     * <p>Cette attente est <b>necessaire et non defensive</b>. Le dernier evenement est
     * ecrit dans la meme transaction que le passage a l'etat terminal : a l'instant ou ce
     * statut devient observable, l'evenement existe deja mais n'est pas encore publie. Le
     * relais est asynchrone par construction — c'est precisement ce que l'outbox apporte,
     * la transaction metier ne depend pas du bus.
     *
     * <p>Affirmer directement que tout est publie apres avoir attendu le statut revient
     * donc a attendre un etat puis a asserter une propriete qui, par construction, arrive
     * apres lui. C'est la forme la plus courante d'assertion implicitement temporelle.
     */
    protected void awaitOutboxDrained(String transactionId) {
        await().atMost(SETTLE_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> org.assertj.core.api.Assertions
                        .assertThat(unpublishedOutboxCount(transactionId))
                        .as("%s", diagnostics(transactionId))
                        .isZero());
    }

    /**
     * Attend qu'un nombre precis de transitions refusees ait ete journalise.
     *
     * <p>Sert de <b>marqueur de consommation</b>. Un message duplique n'a, par definition,
     * aucun effet : rien n'indique en base qu'il a ete traite. Affirmer directement
     * "un seul mouvement a eu lieu" passerait donc a vide si le doublon n'etait pas encore
     * arrive. Attendre le refus qu'il provoque — ou celui d'un message marqueur envoye
     * derriere lui sur la meme partition, donc traite apres lui — prouve qu'il a ete vu.
     *
     * <p>Le nombre exact compte : un refus de trop signalerait qu'un doublon a franchi la
     * deduplication et n'a ete arrete que par la machine a etats.
     */
    protected void awaitRejectedTransitions(String transactionId, int expected) {
        await().atMost(SETTLE_TIMEOUT)
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> org.assertj.core.api.Assertions
                        .assertThat(rejectedTransitions(transactionId))
                        .as("%s", diagnostics(transactionId))
                        .hasSize(expected));
    }

    protected String statusOf(String transactionId) {
        return jdbc.sql("SELECT status FROM payment.payment_transaction WHERE id = :id")
                .param("id", UUID.fromString(transactionId))
                .query(String.class)
                .single();
    }

    /** Transitions acceptees, sous la forme {@code DE->VERS}, dans l'ordre. */
    protected List<String> acceptedTransitions(String transactionId) {
        return jdbc.sql("""
                        SELECT COALESCE(from_status, '-') || '->' || to_status AS step
                          FROM payment.transaction_state_transition
                         WHERE transaction_id = :id AND accepted = true
                         ORDER BY seq
                        """)
                .param("id", UUID.fromString(transactionId))
                .query(String.class)
                .list();
    }

    /** Transitions refusees, sous la forme {@code DE->VERS(motif)}. */
    protected List<String> rejectedTransitions(String transactionId) {
        return jdbc.sql("""
                        SELECT COALESCE(from_status, '-') || '->' || to_status
                               || '(' || COALESCE(rejection_reason, '?') || ')' AS step
                          FROM payment.transaction_state_transition
                         WHERE transaction_id = :id AND accepted = false
                         ORDER BY seq
                        """)
                .param("id", UUID.fromString(transactionId))
                .query(String.class)
                .list();
    }

    protected List<String> outboxEventTypes(String transactionId) {
        return jdbc.sql("""
                        SELECT event_type FROM payment.outbox_event
                         WHERE aggregate_id = :id ORDER BY seq
                        """)
                .param("id", transactionId)
                .query(String.class)
                .list();
    }

    protected long unpublishedOutboxCount(String transactionId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM payment.outbox_event
                         WHERE aggregate_id = :id AND published_at IS NULL
                        """)
                .param("id", transactionId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    protected long processedMessageCount(String eventId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM payment.processed_message WHERE event_id = :id")
                .param("id", eventId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    protected String ledgerEntryRefOf(String transactionId) {
        return jdbc.sql("SELECT ledger_entry_ref FROM payment.payment_transaction WHERE id = :id")
                .param("id", UUID.fromString(transactionId))
                .query(String.class)
                .single();
    }

    protected String diagnostics(String transactionId) {
        return """
                transaction %s
                  statut              : %s
                  transitions ok      : %s
                  transitions refusees: %s
                  outbox              : %s (non publies : %d)
                  appels grand livre  : %d
                """.formatted(transactionId, safeStatus(transactionId),
                acceptedTransitions(transactionId), rejectedTransitions(transactionId),
                outboxEventTypes(transactionId), unpublishedOutboxCount(transactionId),
                ledgerCallsFor(transactionId));
    }

    private String safeStatus(String transactionId) {
        try {
            return statusOf(transactionId);
        } catch (Exception e) {
            return "introuvable";
        }
    }

    // --- Appels HTTP ------------------------------------------------------------------

    protected String defaultToken() {
        return jwtIssuer.token("marchand", "payment-service",
                com.ocb.platform.security.OcbScopes.PAYMENT_INITIATE,
                com.ocb.platform.security.OcbScopes.PAYMENT_READ);
    }

    protected ApiResponse post(String path, String idempotencyKey, String body) {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(HttpMethod.POST, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Authorization", "Bearer " + defaultToken());
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return execute(request);
    }

    protected ApiResponse get(String path) {
        return execute(MockMvcRequestBuilders.request(HttpMethod.GET, URI.create(path))
                .header("Authorization", "Bearer " + defaultToken()));
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

    /**
     * Demande un encaissement et rend l'identifiant de la transaction creee.
     *
     * <p>Les deux derniers chiffres du montant pilotent le comportement de l'operateur
     * simule : {@code 98} refus, {@code 97} silence, {@code 96} succes publie deux fois.
     */
    protected String requestCollection(String amount) {
        String label = "flow-" + suffix + "-" + amount;
        ApiResponse response = post("/v1/collections", label, """
                {
                  "externalRef": "TX-%s",
                  "amount": "%s",
                  "currency": "XAF",
                  "payerMsisdn": "+237670000001",
                  "walletAccountRef": "2100.wallet-%s",
                  "providerCode": "MTN_MOMO"
                }
                """.formatted(label, amount, suffix));

        org.assertj.core.api.Assertions.assertThat(response.status())
                .as("demande d'encaissement : %s", response.body())
                .isEqualTo(202);
        return response.transactionId();
    }

    public record ApiResponse(int status, JsonNode body) {

        public String transactionId() {
            return body.get("transactionId").asText();
        }
    }
}
