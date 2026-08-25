package com.ocb.provider;

import com.ocb.platform.kafka.ProcessedMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La deduplication technique, cote operateur.
 *
 * <p>Meme raison qu'en face : le schema vient desormais de
 * {@code ocb.kafka.consumer.schema}, et une valeur fausse ne se verrait qu'a la premiere
 * commande consommee. Les deux services ont chacun leur propriete, donc chacun son test —
 * verifier l'un ne dit rien de l'autre.
 */
class ProcessedMessageStoreIT extends ProviderPersistenceTestBase {

    private static final String GROUP = "provider-service.commands";

    @Autowired
    private ProcessedMessageStore processed;

    @Test
    @DisplayName("une commande redelivree n'est executee qu'une fois")
    void redeliveredCommandIsRefused() {
        // Le cas concret : payment-service publie une commande d'encaissement, le courtier
        // la redelivre apres un redemarrage. La rejouer appellerait l'operateur deux fois,
        // donc debiterait potentiellement le client deux fois.
        String eventId = "cmd-" + suffix;

        assertThat(processed.markProcessed(GROUP, eventId, "provider.collection.execute")).isTrue();
        assertThat(processed.markProcessed(GROUP, eventId, "provider.collection.execute")).isFalse();
    }

    @Test
    @DisplayName("la ligne atterrit dans le schema de ce service")
    void writesIntoTheConfiguredSchema() {
        String eventId = "cmd-schema-" + suffix;
        processed.markProcessed(GROUP, eventId, "provider.collection.execute");

        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM provider.processed_message WHERE event_id = :id
                        """)
                .param("id", eventId)
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1L);
    }
}
