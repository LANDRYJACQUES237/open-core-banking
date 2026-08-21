package com.ocb.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Socle des tests d'integration.
 *
 * <p>Un seul conteneur PostgreSQL est partage par toutes les classes de test. Consequence
 * a assumer : les donnees persistent d'une classe a l'autre, et on ne peut pas nettoyer
 * entre deux tests puisque les ecritures sont immuables et non supprimables. Ce n'est pas
 * une gene mais une discipline utile — chaque test travaille sur ses propres comptes, ou
 * raisonne en variation de solde plutot qu'en valeur absolue, exactement comme il faudra
 * le faire sur un grand livre reel.
 *
 * <p>Les appels passent par MockMvc plutot que par un serveur ecoutant sur un port. La
 * chaine Spring MVC complete est traversee — filtres, validation du contrat, controleurs
 * generes, gestionnaire d'erreurs — sans lier de port ni ouvrir de {@code Selector} NIO.
 * C'est plus rapide, strictement deterministe, et cela evite de faire dependre la suite
 * de tests de la pile reseau de la machine hote.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class LedgerIntegrationTestBase {

    protected static final String OWNER_USER = "ledger_owner";
    protected static final String OWNER_PASSWORD = "owner-secret";
    protected static final String APP_USER = "ledger_app";
    protected static final String APP_PASSWORD = "app-secret";

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("ledger")
                    .withUsername(OWNER_USER)
                    .withPassword(OWNER_PASSWORD)
                    .withInitScript("db/testcontainers-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        // L'application tourne sous le role restreint, pas sous le proprietaire du schema.
        // C'est ce qui permet de prouver qu'elle n'a jamais besoin de modifier une ecriture.
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        // Les taches planifiees sont appelees explicitement par les tests : une
        // consolidation qui se declenche en arriere-plan pendant une assertion
        // transformerait un echec deterministe en echec intermittent.
        registry.add("ledger.maintenance.enabled", () -> "false");
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected DataSource appDataSource;

    protected String suffix;

    @BeforeEach
    void freshSuffix() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // --- Acces direct a la base -------------------------------------------------------

    /** Connexion sous le proprietaire du schema : contourne les droits, pas les triggers. */
    protected Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD);
    }

    /** Connexion sous le role d'execution : soumise aux droits accordes par la migration V5. */
    protected Connection appConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
    }

    // --- Appels HTTP ------------------------------------------------------------------

    protected ApiResponse post(String path, String idempotencyKey, String body) {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.request(HttpMethod.POST, URI.create(path))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
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

    // --- Raccourcis metier ------------------------------------------------------------

    /** Ouvre un portefeuille client sous le compte de regroupement 2100. */
    protected String openWallet(String name) {
        String accountNumber = "2100.%s-%s".formatted(name, suffix);
        ApiResponse response = post("/v1/accounts", "open-" + accountNumber, """
                {
                  "accountNumber": "%s",
                  "accountType": "LIABILITY",
                  "currency": "XAF",
                  "ownerRef": "%s",
                  "name": "Portefeuille de test"
                }
                """.formatted(accountNumber, name + "-" + suffix));

        assertThat(response.status())
                .as("ouverture du portefeuille %s : %s", accountNumber, response.body())
                .isEqualTo(201);
        return accountNumber;
    }

    protected ApiResponse postEntry(String idempotencyKey, String description, String... lines) {
        return post("/v1/journal-entries", idempotencyKey, """
                {
                  "description": "%s",
                  "lines": [%s]
                }
                """.formatted(description, String.join(",", lines)));
    }

    protected static String line(String accountNumber, String direction, String amount) {
        return """
                {"accountNumber":"%s","direction":"%s","amount":"%s","currency":"XAF"}
                """.formatted(accountNumber, direction, amount);
    }

    protected java.math.BigDecimal balanceOf(String accountNumber) {
        ApiResponse response = get("/v1/accounts/%s/balance".formatted(accountNumber));
        assertThat(response.status())
                .as("solde de %s : %s", accountNumber, response.body())
                .isEqualTo(200);
        return new java.math.BigDecimal(response.body().get("balance").asText());
    }

    public record ApiResponse(int status, JsonNode body) {

        public String code() {
            return body.has("code") ? body.get("code").asText() : null;
        }

        public String entryRef() {
            return body.get("entryRef").asText();
        }
    }
}
