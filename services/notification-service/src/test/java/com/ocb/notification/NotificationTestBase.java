package com.ocb.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.platform.security.OcbScopes;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
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
 * <p>Presque tout ce que fait ce service se verifie sans bus : rediger un message, choisir
 * son canal, l'inscrire dans une trace immuable. Seule la consommation elle-meme —
 * deduplication d'une redelivrance, mise au rebut d'un message definitivement illisible —
 * exige un courtier, et vit dans son propre socle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(
        com.ocb.platform.security.test.TestSecurityConfiguration.class)
public abstract class NotificationTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("notification")
                    .withUsername("notification_owner")
                    .withPassword("owner-secret")
                    .withInitScript("db/testcontainers-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "notification_app");
        registry.add("spring.datasource.password", () -> "app-secret");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        // Ni consommateur ni declaration de topics : ces tests observent ce qu'une
        // notification laisse en base, pas ce qu'un courtier en fait.
        registry.add("spring.kafka.listener.auto-startup", () -> "false");
        registry.add("spring.kafka.admin.auto-create", () -> "false");
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

    protected String defaultToken() {
        return jwtIssuer.token("exploitation", "notification-service", OcbScopes.NOTIFICATION_READ);
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

    public record ApiResponse(int status, JsonNode body) {
    }
}
