package com.ocb.platform.outbox;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Publie vers Kafka les evenements deposes dans l'outbox.
 *
 * <h2>Pourquoi un relais separe</h2>
 *
 * Parce que publier depuis la transaction metier reintroduit le dual-write. Ici, la
 * transaction metier ne fait qu'ecrire une ligne ; la publication est un travail
 * independant, rejouable, qui peut echouer et reprendre sans consequence.
 *
 * <h2>Livraison au moins une fois, assumee</h2>
 *
 * Un arret entre {@code kafka.send} et la mise a jour de {@code published_at} republiera
 * l'evenement. C'est acceptable et attendu : le meme {@code eventId} est reemis, et les
 * consommateurs idempotents l'absorbent. L'inverse — marquer publie avant d'envoyer —
 * donnerait une livraison <i>au plus une fois</i>, c'est-a-dire une perte silencieuse, ce
 * qui est inacceptable pour un mouvement d'argent.
 *
 * <h2>Ordonnancement : la limite a connaitre</h2>
 *
 * {@code FOR UPDATE SKIP LOCKED} permettrait a plusieurs relais de travailler en
 * parallele, mais alors rien ne garantirait que deux evenements d'un meme agregat partent
 * dans l'ordre. Deux reponses :
 *
 * <ul>
 *   <li>le relais tourne en <b>une seule instance</b> ({@code replicas: 1}). Suffisant a
 *       cette echelle, et honnete a documenter plutot qu'a cacher ;
 *   <li>surtout, l'ordre <b>par agregat</b> ne depend pas du relais : deux transactions
 *       portant sur la meme entite se serialisent derriere le verrou pessimiste pose sur
 *       sa ligne, donc leurs lignes d'outbox recoivent des numeros croissants dans l'ordre
 *       de validation. Le relais n'a plus qu'a respecter cet ordre, ce que fait
 *       {@code ORDER BY seq}.
 * </ul>
 *
 * <p>Un echec de publication <b>interrompt le lot</b> plutot que de passer au suivant : si
 * l'evenement 5 d'une transaction ne part pas, publier le 6 livrerait les faits dans le
 * desordre a un consommateur qui n'a aucun moyen de le savoir.
 *
 * <h2>Evolution</h2>
 *
 * La table respecte la convention de l'Outbox Event Router de Debezium
 * ({@code aggregate_type}, {@code aggregate_id}, {@code event_type}, {@code payload}).
 * Passer du polling au CDC en Phase 5 sera un changement de configuration, pas une
 * reecriture.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Borne le drainage d'un cycle, pour qu'un afflux ne monopolise pas le fil planifie. */
    private static final int MAX_BATCHES_PER_CYCLE = 20;

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final OutboxProperties properties;

    /**
     * Transactions pilotees explicitement plutot que par {@code @Transactional}.
     *
     * <p>Deux raisons. D'abord, {@code publishBatch} est appele depuis une methode de la
     * meme classe : une annotation serait contournee par le proxy Spring et ne creerait
     * aucune transaction — bug classique et silencieux. Ensuite, l'enregistrement d'un
     * echec doit survivre a l'echec lui-meme, ce qui demande une transaction distincte de
     * celle du lot.
     */
    private final TransactionTemplate batchTransaction;
    private final TransactionTemplate failureTransaction;

    public OutboxRelay(JdbcClient jdbc,
                       KafkaTemplate<String, String> kafka,
                       OutboxProperties properties,
                       org.springframework.transaction.PlatformTransactionManager transactionManager,
                       MeterRegistry meters) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.properties = properties;

        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTransaction = new TransactionTemplate(transactionManager);
        this.failureTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        meters.gauge("ocb.outbox.pending.age.seconds", this, OutboxRelay::oldestPendingAgeSeconds);
        meters.gauge("ocb.outbox.pending.count", this, relay -> relay.pendingCount());
    }

    @Scheduled(fixedDelayString = "${ocb.outbox.poll-interval:PT1S}")
    public void publishPending() {
        try {
            for (int cycle = 0; cycle < MAX_BATCHES_PER_CYCLE; cycle++) {
                if (publishBatch() < properties.getBatchSize()) {
                    return;
                }
            }
        } catch (Exception e) {
            // Le relais ne doit jamais mourir : une exception qui remonte arreterait
            // definitivement la tache planifiee et les evenements s'accumuleraient sans
            // que rien ne le signale.
            log.error("Cycle de publication outbox interrompu, reprise au prochain declenchement", e);
        }
    }

    /**
     * Publie un lot et retourne le nombre d'evenements effectivement publies.
     *
     * <p>Ne leve pas sur echec de publication : la ligne reste a {@code published_at IS
     * NULL} et repartira au cycle suivant. Lever ferait perdre le travail deja accompli
     * dans le lot, et surtout annulerait l'enregistrement de la tentative.
     */
    public int publishBatch() {
        Integer published = batchTransaction.execute(status -> {
            List<PendingEvent> batch = fetchPending();
            if (batch.isEmpty()) {
                return 0;
            }

            int count = 0;
            for (PendingEvent event : batch) {
                SendResult<String, String> result;
                try {
                    // Envoi synchrone, volontairement. En asynchrone, le marquage
                    // "publie" deviendrait independant de la reussite reelle, ce qui
                    // ramenerait le dual-write a l'interieur meme du relais.
                    result = kafka.send(event.topic(), event.partitionKey(), event.payload())
                            .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return count;
                } catch (Exception e) {
                    recordFailure(event, e);
                    // Interruption du lot : publier les evenements suivants livrerait les
                    // faits dans le desordre.
                    return count;
                }
                markPublished(event, result);
                count++;
            }
            return count;
        });

        int result = published == null ? 0 : published;
        if (result > 0) {
            log.debug("Outbox : {} evenement(s) publie(s)", result);
        }
        return result;
    }

    private List<PendingEvent> fetchPending() {
        return jdbc.sql("""
                        SELECT id, event_id, topic, partition_key, payload::text AS payload, event_type
                          FROM %s.outbox_event
                         WHERE published_at IS NULL
                         ORDER BY seq
                         FOR UPDATE SKIP LOCKED
                         LIMIT :batchSize
                        """.formatted(properties.getSchema()))
                .param("batchSize", properties.getBatchSize())
                .query((rs, rowNum) -> new PendingEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_id"),
                        rs.getString("topic"),
                        rs.getString("partition_key"),
                        rs.getString("payload"),
                        rs.getString("event_type")))
                .list();
    }

    private void markPublished(PendingEvent event, SendResult<String, String> result) {
        jdbc.sql("""
                        UPDATE %s.outbox_event
                           SET published_at = now(), attempts = attempts + 1,
                               kafka_partition = :partition, kafka_offset = :offset
                         WHERE id = :id
                        """.formatted(properties.getSchema()))
                .param("id", event.id())
                .param("partition", result.getRecordMetadata().partition())
                .param("offset", result.getRecordMetadata().offset())
                .update();
    }

    /** Dans sa propre transaction, pour survivre a l'annulation du lot. */
    private void recordFailure(PendingEvent event, Exception cause) {
        String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        String truncated = reason.length() > 500 ? reason.substring(0, 500) : reason;

        failureTransaction.executeWithoutResult(status ->
                jdbc.sql("""
                                UPDATE %s.outbox_event
                                   SET attempts = attempts + 1, last_error = :error
                                 WHERE id = :id
                                """.formatted(properties.getSchema()))
                        .param("id", event.id())
                        .param("error", truncated)
                        .update());

        log.warn("Publication de {} ({}) en echec, nouvelle tentative au prochain cycle : {}",
                event.eventId(), event.eventType(), truncated);
    }

    /**
     * Age du plus vieil evenement non publie.
     *
     * <p>C'est la metrique la plus utile du systeme : elle ne mesure pas si le relais
     * tourne, mais s'il tient la cadence. Un compteur d'evenements publies resterait a
     * zero aussi bien quand tout est publie que quand plus rien ne l'est.
     */
    public double oldestPendingAgeSeconds() {
        try {
            Double age = jdbc.sql("""
                            SELECT COALESCE(EXTRACT(EPOCH FROM (now() - MIN(created_at))), 0)
                              FROM %s.outbox_event
                             WHERE published_at IS NULL
                            """.formatted(properties.getSchema()))
                    .query(Double.class)
                    .single();
            return age == null ? 0d : age;
        } catch (Exception e) {
            // Une metrique qui leve ferait tomber tout le scrape Prometheus.
            return -1d;
        }
    }

    public long pendingCount() {
        try {
            Long count = jdbc.sql("SELECT COUNT(*) FROM %s.outbox_event WHERE published_at IS NULL"
                            .formatted(properties.getSchema()))
                    .query(Long.class)
                    .single();
            return count == null ? 0L : count;
        } catch (Exception e) {
            return -1L;
        }
    }

    /** Supprime les evenements publies au-dela de la retention configuree. */
    public int purgePublished() {
        Duration retention = properties.getRetention();
        Integer deleted = batchTransaction.execute(status -> jdbc.sql("""
                        DELETE FROM %s.outbox_event
                         WHERE published_at IS NOT NULL
                           AND published_at < now() - make_interval(secs => :seconds)
                        """.formatted(properties.getSchema()))
                .param("seconds", (double) retention.toSeconds())
                .update());
        return deleted == null ? 0 : deleted;
    }

    private record PendingEvent(UUID id, String eventId, String topic, String partitionKey,
                                String payload, String eventType) {
    }
}
