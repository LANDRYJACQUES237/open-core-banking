package com.ocb.notification;

import com.ocb.notification.application.PaymentEventNotifier;
import com.ocb.notification.domain.Notification;
import com.ocb.notification.domain.NotificationChannel;
import com.ocb.notification.domain.NotificationType;
import com.ocb.notification.domain.port.NotificationStore;
import com.ocb.platform.events.Payloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ce qu'un evenement de paiement laisse comme trace.
 *
 * <p>Le fil conducteur de ces tests est le <b>destinataire</b>. Il est designe par une
 * reference de portefeuille et jamais par un numero de telephone, parce qu'aucun service
 * de la plateforme ne conserve le numero en clair. C'est une consequence directe de la
 * decision prise en Phase 2 — ne pas conserver la donnee plutot que la chiffrer — et elle
 * se paie ici : ce service sait qui prevenir, pas comment le joindre.
 */
class NotificationDeliveryIT extends NotificationTestBase {

    @Autowired
    private PaymentEventNotifier notifier;

    @Autowired
    private NotificationStore store;

    @Test
    @DisplayName("un encaissement abouti previent le porteur du portefeuille credite")
    void collectionCompletedNotifiesTheWalletHolder() {
        UUID transactionId = UUID.randomUUID();
        String wallet = "2100.wallet-" + suffix;

        notifier.onCollectionCompleted(new Payloads.PaymentCollectionCompleted(
                transactionId.toString(), "TX-" + suffix, "10000", "XAF",
                "100", "150", wallet, "JE-" + suffix, "+2376****0001"), "corr-" + suffix);

        List<Notification> emitted = store.findByTransaction(transactionId);
        assertThat(emitted).hasSize(1);

        Notification notification = emitted.get(0);
        assertThat(notification.type()).isEqualTo(NotificationType.COLLECTION_COMPLETED);
        assertThat(notification.channel()).isEqualTo(NotificationChannel.CUSTOMER);
        assertThat(notification.recipientRef())
                .as("le destinataire est un compte, jamais un numero")
                .isEqualTo(wallet);
        assertThat(notification.message()).contains("10000", "XAF");
    }

    @Test
    @DisplayName("un decaissement compense annonce le remboursement au bon portefeuille")
    void reversalNotifiesTheDebitedWallet() {
        UUID transactionId = UUID.randomUUID();
        String wallet = "2100.wallet-" + suffix;

        notifier.onDisbursementReversed(new Payloads.PaymentDisbursementReversed(
                transactionId.toString(), "TX-" + suffix, "5000", "XAF", wallet,
                "DISB-RES-x", "JE-REV-x", "PAYEE_UNKNOWN", "Beneficiaire inconnu",
                "+2376****0001"), "corr-" + suffix);

        Notification notification = store.findByTransaction(transactionId).get(0);
        assertThat(notification.type()).isEqualTo(NotificationType.DISBURSEMENT_REVERSED);
        assertThat(notification.recipientRef()).isEqualTo(wallet);
        assertThat(notification.message())
                .as("le client doit apprendre que son argent est revenu")
                .containsIgnoringCase("recredite");
    }

    @Test
    @DisplayName("un transfert previent l'emetteur, pas le destinataire")
    void transferNotifiesTheSender() {
        UUID transactionId = UUID.randomUUID();
        String from = "2100.wallet-from-" + suffix;
        String to = "2100.wallet-to-" + suffix;

        notifier.onTransferCompleted(new Payloads.PaymentTransferCompleted(
                transactionId.toString(), "TX-" + suffix, "2000", "XAF", "20",
                from, to, "JE-" + suffix), "corr-" + suffix);

        // C'est l'emetteur qui a decide, et lui dont le solde a diminue. Prevenir le
        // destinataire supposerait de savoir qu'il souhaite l'etre — une question de
        // consentement que ce service n'a pas les moyens de trancher.
        assertThat(store.findByTransaction(transactionId).get(0).recipientRef()).isEqualTo(from);
    }

    @Test
    @DisplayName("une revue manuelle part vers l'exploitation, jamais vers le client")
    void manualReviewGoesToOperations() {
        UUID transactionId = UUID.randomUUID();

        notifier.onManualReviewRequired(new Payloads.PaymentManualReviewRequired(
                transactionId.toString(), "TX-" + suffix, "MANUAL_REVIEW",
                "budget de polling epuise"), "corr-" + suffix);

        Notification notification = store.findByTransaction(transactionId).get(0);
        assertThat(notification.channel())
                .as("annoncer une incertitude sans pouvoir la resoudre inquiete sans aider")
                .isEqualTo(NotificationChannel.OPS);
        assertThat(notification.recipientRef()).isNotEqualTo("2100.wallet-" + suffix);
    }

    @Test
    @DisplayName("une notification emise ne peut plus etre modifiee ni supprimee")
    void emittedNotificationsAreImmutable() {
        UUID transactionId = UUID.randomUUID();
        notifier.onCollectionFailed(new Payloads.PaymentCollectionFailed(
                transactionId.toString(), "TX-" + suffix, "10000", "XAF",
                "2100.wallet-" + suffix, "PROVIDER_DECLINED", "Refuse", "+2376****0001"),
                "corr-" + suffix);

        // "Le client avait-il ete prevenu ?" est une question qui se pose en litige. La
        // reponse ne vaut que si personne n'a pu la retoucher entre-temps — declencheur et
        // droits le refusent tous deux.
        assertThatThrownBy(() -> jdbc.sql(
                        "UPDATE notification.notification SET message = 'autre chose' "
                                + "WHERE transaction_id = :id")
                .param("id", transactionId)
                .update())
                .isInstanceOf(DataAccessException.class);

        assertThatThrownBy(() -> jdbc.sql(
                        "DELETE FROM notification.notification WHERE transaction_id = :id")
                .param("id", transactionId)
                .update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("le point de consultation rend ce qui a ete emis")
    void diagnosticsExposeWhatWasSaid() {
        UUID transactionId = UUID.randomUUID();
        notifier.onDisbursementCompleted(new Payloads.PaymentDisbursementCompleted(
                transactionId.toString(), "TX-" + suffix, "5000", "XAF", "50", "25",
                "2100.wallet-" + suffix, "JE-" + suffix, "+2376****0001"), "corr-" + suffix);

        ApiResponse response = get("/v1/notifications/" + transactionId);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).hasSize(1);
        assertThat(response.body().get(0).get("type").asText()).isEqualTo("DISBURSEMENT_COMPLETED");
        assertThat(response.body().get(0).get("channel").asText()).isEqualTo("CUSTOMER");
    }

    @Test
    @DisplayName("une transaction jamais notifiee rend une liste vide, pas une erreur")
    void unknownTransactionReturnsEmpty() {
        // Ne pas avoir notifie est une reponse valable, et souvent celle que l'exploitant
        // cherche a confirmer. Un 404 l'obligerait a distinguer deux cas qui n'en font
        // qu'un pour lui.
        ApiResponse response = get("/v1/notifications/" + UUID.randomUUID());

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body()).isEmpty();
    }
}
