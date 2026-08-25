package com.ocb.notification.application;

import com.ocb.notification.domain.Notification;
import com.ocb.notification.domain.NotificationType;
import com.ocb.notification.domain.port.NotificationSender;
import com.ocb.notification.domain.port.NotificationStore;
import com.ocb.platform.events.Payloads;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Traduit un evenement de paiement en message.
 *
 * <p>Chaque methode s'execute dans la transaction du consommateur, qui a deja enregistre
 * le message comme traite. Consequence a garder en tete, la meme que partout ailleurs sur
 * la plateforme : <b>lever une exception annule tout</b>, y compris cet enregistrement, et
 * le message sera redelivre. C'est voulu — mieux vaut risquer de prevenir deux fois que de
 * ne pas prevenir du tout.
 *
 * <p>Ce service ne publie aucun evenement. Une notification est un point terminal : rien
 * n'en attend la suite.
 */
@Service
public class PaymentEventNotifier {

    private final NotificationStore store;
    private final NotificationSender sender;

    public PaymentEventNotifier(NotificationStore store, NotificationSender sender) {
        this.store = store;
        this.sender = sender;
    }

    @Transactional
    public void onCollectionCompleted(Payloads.PaymentCollectionCompleted event, String correlationId) {
        emit(NotificationType.COLLECTION_COMPLETED, event.transactionId(),
                event.walletAccountRef(), event.amount(), event.currency(),
                event.externalRef(), correlationId);
    }

    @Transactional
    public void onCollectionFailed(Payloads.PaymentCollectionFailed event, String correlationId) {
        emit(NotificationType.COLLECTION_FAILED, event.transactionId(),
                event.walletAccountRef(), event.amount(), event.currency(),
                event.externalRef(), correlationId);
    }

    @Transactional
    public void onDisbursementCompleted(Payloads.PaymentDisbursementCompleted event,
                                        String correlationId) {
        emit(NotificationType.DISBURSEMENT_COMPLETED, event.transactionId(),
                event.walletAccountRef(), event.amount(), event.currency(),
                event.externalRef(), correlationId);
    }

    @Transactional
    public void onDisbursementReversed(Payloads.PaymentDisbursementReversed event,
                                       String correlationId) {
        emit(NotificationType.DISBURSEMENT_REVERSED, event.transactionId(),
                event.walletAccountRef(), event.amount(), event.currency(),
                event.externalRef(), correlationId);
    }

    @Transactional
    public void onTransferCompleted(Payloads.PaymentTransferCompleted event, String correlationId) {
        // C'est l'emetteur qu'on previent : c'est lui qui a decide, et lui dont le solde a
        // diminue du montant augmente des frais.
        emit(NotificationType.TRANSFER_COMPLETED, event.transactionId(),
                event.fromWalletAccountRef(), event.amount(), event.currency(),
                event.externalRef(), correlationId);
    }

    @Transactional
    public void onManualReviewRequired(Payloads.PaymentManualReviewRequired event,
                                       String correlationId) {
        // Canal interne, decide par le type et non par cet appel. Le destinataire est
        // l'exploitation, pas un portefeuille : la reference employee est celle du
        // dossier a ouvrir.
        emit(NotificationType.MANUAL_REVIEW_REQUIRED, event.transactionId(),
                "ops", null, null, event.externalRef(), correlationId);
    }

    private void emit(NotificationType type,
                      String transactionId,
                      String recipientRef,
                      String amount,
                      String currency,
                      String reference,
                      String correlationId) {

        Notification notification = new Notification(
                UUID.randomUUID(),
                UUID.fromString(transactionId),
                type,
                NotificationComposer.channelFor(type),
                recipientRef,
                NotificationComposer.compose(type, amount, currency, reference),
                correlationId,
                OffsetDateTime.now());

        // La trace d'abord, la remise ensuite. Dans cet ordre : une remise dont il ne
        // resterait rien serait indefendable en litige, alors qu'une trace sans remise se
        // constate et se rattrape.
        store.record(notification);
        sender.send(notification);
    }
}
