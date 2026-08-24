package com.ocb.provider;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.ocb.platform.domain.money.Money;
import com.ocb.provider.application.CollectionExecutionService;
import com.ocb.provider.application.ReconciliationPoller;
import com.ocb.provider.domain.ProviderCode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * Socle des tests ou l'operateur est un vrai serveur HTTP.
 *
 * <p><b>Pourquoi un serveur et non un objet simule.</b> Toute la doctrine de ce service
 * repose sur une distinction entre une reponse et une absence de reponse. Un objet en
 * processus qui leverait une exception ne testerait que le bloc {@code catch} ; il ne
 * dirait rien de ce qui se passe quand un serveur accepte la connexion, garde le fil
 * ouvert, et ne repond jamais. WireMock, lui, peut faire exactement cela.
 *
 * <p>Le delai de lecture est raccourci a une seconde pour que ces tests restent rapides.
 * C'est le seul reglage ajuste : le reste du comportement est celui de production.
 */
@SpringBootTest
public abstract class ProviderOperatorTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("provider")
                    .withUsername("provider_owner")
                    .withPassword("owner-secret")
                    .withInitScript("db/testcontainers-init.sql");

    /** L'operateur. Un vrai serveur, qui peut vraiment tarder a repondre. */
    protected static final WireMockServer OPERATOR = new WireMockServer(options().dynamicPort());

    static {
        POSTGRES.start();
        OPERATOR.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "provider_app");
        registry.add("spring.datasource.password", () -> "app-secret");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("ocb.provider.operators.base-urls.MTN_MOMO", OPERATOR::baseUrl);
        registry.add("ocb.provider.operators.read-timeout", () -> "PT1S");
        registry.add("ocb.provider.operators.connect-timeout", () -> "PT1S");
        registry.add("ocb.provider.webhook.secrets.MTN_MOMO", () -> "secret-de-test-mtn");

        // La relance est pilotee explicitement par les tests : une tache de fond qui se
        // declencherait entre deux assertions rendrait les resultats intermittents.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
        registry.add("ocb.outbox.enabled", () -> "false");
        registry.add("ocb.provider.poll.interval", () -> "PT1H");
    }

    @Autowired
    protected CollectionExecutionService collections;

    @Autowired
    protected ReconciliationPoller poller;

    @Autowired
    protected JdbcClient jdbc;

    protected String suffix;

    @BeforeEach
    void resetStubs() {
        OPERATOR.resetAll();
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // --- Comportements de l'operateur ---------------------------------------------------

    protected void operatorAccepts(String providerRef) {
        OPERATOR.stubFor(WireMock.post(urlEqualTo("/collections")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"providerRef\":\"%s\",\"status\":\"PENDING\"}".formatted(providerRef))));
    }

    /**
     * L'operateur accepte la connexion puis ne repond pas assez vite.
     *
     * <p>C'est le cas qui compte : ni une erreur, ni un refus. Le fil reste ouvert, le
     * delai de lecture expire, et personne ne sait si la demande a ete prise en compte.
     */
    protected void operatorHangs() {
        OPERATOR.stubFor(WireMock.post(urlEqualTo("/collections")).willReturn(aResponse()
                .withStatus(200)
                .withFixedDelay(3000)
                .withBody("{\"providerRef\":\"trop-tard\",\"status\":\"PENDING\"}")));
    }

    protected void operatorReturnsServerError() {
        OPERATOR.stubFor(WireMock.post(urlEqualTo("/collections"))
                .willReturn(aResponse().withStatus(503)));
    }

    protected void operatorRejects() {
        OPERATOR.stubFor(WireMock.post(urlEqualTo("/collections"))
                .willReturn(aResponse().withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"errorCode\":\"INVALID_MSISDN\"}")));
    }

    protected void statusIs(String status, String extraJson) {
        OPERATOR.stubFor(WireMock.get(urlPathMatching("/collections/.*/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"providerRef\":\"MTN-%s\",\"status\":\"%s\"%s}"
                                .formatted(suffix, status, extraJson))));
    }

    protected void statusHangs() {
        OPERATOR.stubFor(WireMock.get(urlPathMatching("/collections/.*/status"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(3000)
                        .withBody("{\"status\":\"PENDING\"}")));
    }

    // --- Declenchement -----------------------------------------------------------------

    protected UUID requestCollection() {
        UUID transactionId = UUID.randomUUID();
        collections.execute(transactionId, ProviderCode.MTN_MOMO, "TX-" + suffix,
                "collection:" + transactionId, Money.parse("10000", "XAF"),
                "+237670000001", "corr-" + suffix);
        return transactionId;
    }

    /**
     * Rend la relance immediatement due, sans attendre.
     *
     * <p>Manipuler l'echeance plutot que dormir garde le test deterministe : on controle
     * le temps au lieu de l'esperer.
     */
    protected void makePollDue(UUID transactionId) {
        jdbc.sql("""
                        UPDATE provider.provider_operation
                           SET next_poll_at = now() - interval '1 second'
                         WHERE transaction_id = :id
                        """)
                .param("id", transactionId)
                .update();
    }

    /**
     * Recule la date de creation, comme si l'operation attendait depuis des heures.
     *
     * <p>Le budget se mesurant depuis la premiere emission, c'est ainsi qu'on atteint son
     * epuisement en une tentative au lieu d'attendre vingt-quatre heures.
     */
    protected void ageOperation(UUID transactionId, int hours) {
        jdbc.sql("""
                        UPDATE provider.provider_operation
                           SET created_at = now() - make_interval(hours => :hours)
                         WHERE transaction_id = :id
                        """)
                .param("id", transactionId)
                .param("hours", hours)
                .update();
    }

    // --- Observation -------------------------------------------------------------------

    protected String statusOf(UUID transactionId) {
        return jdbc.sql("SELECT status FROM provider.provider_operation WHERE transaction_id = :id")
                .param("id", transactionId).query(String.class).single();
    }

    protected List<String> outboxEventTypes(UUID transactionId) {
        return jdbc.sql("""
                        SELECT event_type FROM provider.outbox_event
                         WHERE aggregate_id = :id ORDER BY seq
                        """)
                .param("id", transactionId.toString()).query(String.class).list();
    }

    protected boolean pollScheduled(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.sql("""
                        SELECT next_poll_at IS NOT NULL FROM provider.provider_operation
                         WHERE transaction_id = :id
                        """)
                .param("id", transactionId).query(Boolean.class).single());
    }

    protected int pollAttemptsOf(UUID transactionId) {
        return jdbc.sql("""
                        SELECT poll_attempts FROM provider.provider_operation WHERE transaction_id = :id
                        """)
                .param("id", transactionId).query(Integer.class).single();
    }
}
