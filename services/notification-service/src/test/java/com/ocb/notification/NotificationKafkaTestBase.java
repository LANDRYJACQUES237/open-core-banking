package com.ocb.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Socle des tests qui traversent reellement Kafka.
 *
 * <p>Ce que ces tests etablissent ne peut pas l'etre autrement. La deduplication d'une
 * redelivrance et la mise au rebut d'un message definitivement illisible sont des
 * proprietes du <b>couple</b> consommateur-courtier : appeler la couche application
 * directement, comme le fait {@code NotificationDeliveryIT}, court-circuite exactement le
 * mecanisme qu'on veut eprouver.
 *
 * <p><b>Contrainte a respecter si une seconde classe Kafka apparait.</b> Elle doit heriter
 * de ce socle sans redefinir la moindre propriete : un {@code @TestPropertySource} sur une
 * sous-classe ferait construire un second contexte, et deux jeux de consommateurs
 * liraient les memes topics en ecrivant dans la meme base.
 *
 * <p><b>Pourquoi la configuration de securite de test est importee ici aussi</b>, alors
 * qu'aucun de ces tests n'emet la moindre requete HTTP. La chaine de securite est
 * construite au <b>demarrage du contexte</b>, pas au premier appel : elle reclame un
 * decodeur de jetons, lequel va chercher le JWKS chez le fournisseur d'identite. Sans
 * doublure, le contexte refuse de demarrer parce qu'aucun Keycloak ne tourne — et l'echec
 * se presente comme une panne du consommateur Kafka, ce qu'il n'est pas.
 */
@SpringBootTest
@org.springframework.context.annotation.Import(
        com.ocb.platform.security.test.TestSecurityConfiguration.class)
public abstract class NotificationKafkaTestBase {

    protected static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(30);

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("notification")
                    .withUsername("notification_owner")
                    .withPassword("owner-secret")
                    .withInitScript("db/testcontainers-init.sql");

    // 3.8.1 et non 3.9 : l'entrypoint de l'image 3.9 valide la configuration complete au
    // formatage et refuse l'adresse annoncee que Testcontainers positionne.
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "notification_app");
        registry.add("spring.datasource.password", () -> "app-secret");
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // Groupe unique par execution : deux suites qui partageraient un groupe se
        // repartiraient les partitions et chacune ne verrait qu'une partie des messages.
        registry.add("ocb.kafka.groups.payment-events",
                () -> "notification-test-" + UUID.randomUUID().toString().substring(0, 8));

        // Une seule tentative avant le rebut. Le comportement teste est la mise au rebut
        // elle-meme, pas la patience du backoff : cinq tentatives espacees allongeraient
        // le test sans rien prouver de plus.
        registry.add("ocb.kafka.consumer.retry.max-attempts", () -> "1");
    }

    @Autowired
    protected KafkaTemplate<String, String> kafka;

    @Autowired
    protected JdbcClient jdbc;

    protected final ObjectMapper json = EventJson.mapper();

    protected String suffix;

    @BeforeEach
    void freshSuffix() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
    }

    // --- Emission ----------------------------------------------------------------------

    /** Publie un evenement bien forme, tel que payment-service le produirait. */
    protected void publish(EventEnvelope envelope, String transactionId) {
        try {
            kafka.send(Topics.EVT_PAYMENT, transactionId, json.writeValueAsString(envelope))
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Publication en echec", e);
        }
    }

    /** Publie un message brut, y compris volontairement invalide. */
    protected void publishRaw(String key, String rawMessage) {
        try {
            kafka.send(Topics.EVT_PAYMENT, key, rawMessage)
                    .get(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Publication en echec", e);
        }
    }

    // --- Observation -------------------------------------------------------------------

    protected long notificationCount(UUID transactionId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM notification.notification WHERE transaction_id = :id
                        """)
                .param("id", transactionId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    protected long processedCount(String eventId) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM notification.processed_message WHERE event_id = :id
                        """)
                .param("id", eventId)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    /**
     * Lit le topic de rebut depuis son debut.
     *
     * <p>Un consommateur jetable, avec son propre groupe : il ne doit ni deplacer les
     * decalages du service ni etre influence par eux.
     */
    protected List<String> deadLetterMessages() {
        Properties props = new Properties();
        props.put("bootstrap.servers", KAFKA.getBootstrapServers());
        props.put("group.id", "dlq-observer-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");

        List<String> messages = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.deadLetter(Topics.EVT_PAYMENT)));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            for (ConsumerRecord<String, String> record : records) {
                messages.add(record.value());
            }
        }
        return messages;
    }
}
