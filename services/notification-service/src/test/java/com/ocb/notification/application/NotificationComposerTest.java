package com.ocb.notification.application;

import com.ocb.notification.domain.NotificationChannel;
import com.ocb.notification.domain.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que la plateforme dit a ses clients.
 *
 * <p>Un texte adresse a un client merite d'etre eprouve cas par cas, et il peut l'etre en
 * millisecondes puisque sa redaction est une fonction pure. Ce qui est verifie ici n'est
 * pas la formulation — elle changera — mais deux proprietes qui, elles, ne doivent pas
 * changer : aucun identifiant technique ne fuit vers le client, et un echec dit ce qui est
 * arrive a l'argent.
 */
class NotificationComposerTest {

    private static final String TRANSACTION_ID = "0f4d3d7e-1c9a-4a2b-9f3e-2b7c1d5e6a8f";

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("aucun message client ne contient d'identifiant technique")
    void customerMessagesLeakNoTechnicalIdentifier(NotificationType type) {
        if (NotificationComposer.channelFor(type) != NotificationChannel.CUSTOMER) {
            return;
        }

        String message = NotificationComposer.compose(type, "10000", "XAF", TRANSACTION_ID);

        // Un identifiant n'a aucun sens pour le destinataire, et c'est une surface
        // d'attaque de plus. La reference metier est passee ici volontairement pour que
        // le test echoue si un jour quelqu'un la glisse dans un texte client.
        assertThat(message)
                .as("%s", type)
                .doesNotContain(TRANSACTION_ID)
                .doesNotContain("2100.")
                .doesNotContain("JE-");
    }

    @ParameterizedTest
    @EnumSource(NotificationType.class)
    @DisplayName("chaque type produit un message non vide")
    void everyTypeHasSomethingToSay(NotificationType type) {
        assertThat(NotificationComposer.compose(type, "10000", "XAF", "TX-001"))
                .as("%s", type)
                .isNotBlank();
    }

    @Test
    @DisplayName("un decaissement compense annonce le remboursement, pas seulement l'echec")
    void reversalSaysTheMoneyCameBack() {
        String message = NotificationComposer.compose(
                NotificationType.DISBURSEMENT_REVERSED, "5000", "XAF", "TX-002");

        // Le message le plus important du service. Le client a vu son portefeuille
        // debite ; ne lui annoncer qu'un echec le laisserait chercher son argent, et la
        // compensation n'aurait servi qu'a equilibrer un bilan.
        assertThat(message)
                .containsIgnoringCase("recredite")
                .contains("5000");
    }

    @Test
    @DisplayName("un encaissement echoue precise qu'aucun montant n'a ete debite")
    void collectionFailureReassures() {
        String message = NotificationComposer.compose(
                NotificationType.COLLECTION_FAILED, "10000", "XAF", "TX-003");

        assertThat(message).containsIgnoringCase("aucun montant");
    }

    @Test
    @DisplayName("une revue manuelle ne part jamais vers le client")
    void manualReviewNeverReachesTheCustomer() {
        // La regle est portee par le type et non par l'appelant : une nouvelle branche de
        // code ne peut pas envoyer par inattention un incident interne au porteur du
        // compte.
        assertThat(NotificationComposer.channelFor(NotificationType.MANUAL_REVIEW_REQUIRED))
                .isEqualTo(NotificationChannel.OPS);
    }

    @ParameterizedTest
    @EnumSource(value = NotificationType.class, mode = EnumSource.Mode.EXCLUDE,
            names = "MANUAL_REVIEW_REQUIRED")
    @DisplayName("tout le reste s'adresse au client")
    void everythingElseGoesToTheCustomer(NotificationType type) {
        assertThat(NotificationComposer.channelFor(type)).isEqualTo(NotificationChannel.CUSTOMER);
    }
}
