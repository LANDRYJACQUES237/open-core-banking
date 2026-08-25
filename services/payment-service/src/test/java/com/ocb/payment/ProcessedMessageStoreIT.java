package com.ocb.payment;

import com.ocb.platform.kafka.ProcessedMessageStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La deduplication technique, une fois qu'elle vient d'un module partage.
 *
 * <p>Ce test existe pour une raison precise. Depuis que l'implementation vit dans
 * {@code common-kafka}, le schema qui heberge {@code processed_message} n'est plus ecrit
 * dans le SQL mais fourni par la propriete {@code ocb.kafka.consumer.schema}. Une valeur
 * fausse ne se verrait nulle part au demarrage : le contexte demarrerait, les points
 * d'entree HTTP repondraient, et l'erreur n'apparaitrait qu'a la premiere consommation
 * d'un message — donc en CI, dans un test de flux, avec une cause difficile a lire.
 *
 * <p>Ici, elle echoue immediatement et pour la bonne raison.
 */
class ProcessedMessageStoreIT extends PaymentPersistenceTestBase {

    private static final String GROUP = "payment-service.provider-events";

    @Autowired
    private ProcessedMessageStore processed;

    @Test
    @DisplayName("un message inconnu est accepte, le meme message ne l'est qu'une fois")
    void secondAttemptIsRefused() {
        String eventId = "evt-" + suffix;

        assertThat(processed.markProcessed(GROUP, eventId, "provider.operation.succeeded"))
                .as("premiere reception")
                .isTrue();

        assertThat(processed.markProcessed(GROUP, eventId, "provider.operation.succeeded"))
                .as("redelivraison : l'effet metier ne doit pas etre rejoue")
                .isFalse();
    }

    @Test
    @DisplayName("deux groupes de consommation traitent le meme evenement chacun de leur cote")
    void groupsAreIndependent() {
        // La cle est composite, et ce n'est pas un detail : deux groupes representent deux
        // lectures independantes du meme flux. Si le premier a consommer masquait
        // l'evenement au second, ajouter un consommateur casserait ceux en place — ce qui
        // arrivera des la Phase 4b avec notification-service.
        String eventId = "evt-partage-" + suffix;

        assertThat(processed.markProcessed(GROUP, eventId, "payment.collection.completed")).isTrue();
        assertThat(processed.markProcessed("autre-service.notifications", eventId,
                "payment.collection.completed")).isTrue();
    }

    @Test
    @DisplayName("la ligne atterrit dans le schema de ce service")
    void writesIntoTheConfiguredSchema() {
        // L'assertion qui pinne reellement la propriete : elle nomme le schema attendu,
        // et echouerait si la configuration en designait un autre.
        String eventId = "evt-schema-" + suffix;
        processed.markProcessed(GROUP, eventId, "provider.operation.failed");

        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM payment.processed_message WHERE event_id = :id
                        """)
                .param("id", eventId)
                .query(Long.class)
                .single();

        assertThat(count).isEqualTo(1L);
    }
}
