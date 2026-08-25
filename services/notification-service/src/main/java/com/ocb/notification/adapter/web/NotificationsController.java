package com.ocb.notification.adapter.web;

import com.ocb.notification.api.NotificationsApi;
import com.ocb.notification.api.model.Notification;
import com.ocb.notification.domain.port.NotificationStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class NotificationsController implements NotificationsApi {

    private final NotificationStore store;
    private final NotificationApiMapper mapper;

    public NotificationsController(NotificationStore store, NotificationApiMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<List<Notification>> listNotifications(UUID transactionId) {
        // Une liste vide plutot qu'un 404 : ne pas avoir notifie une transaction est une
        // reponse valable, et souvent celle que l'exploitant cherche a confirmer.
        return ResponseEntity.ok(store.findByTransaction(transactionId).stream()
                .map(mapper::toApi)
                .toList());
    }
}
