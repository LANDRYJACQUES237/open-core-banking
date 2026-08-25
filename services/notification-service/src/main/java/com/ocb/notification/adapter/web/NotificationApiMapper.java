package com.ocb.notification.adapter.web;

import com.ocb.notification.api.model.Notification;
import org.springframework.stereotype.Component;

@Component
public class NotificationApiMapper {

    public Notification toApi(com.ocb.notification.domain.Notification n) {
        return new Notification(
                n.id(),
                n.transactionId(),
                Notification.TypeEnum.fromValue(n.type().name()),
                Notification.ChannelEnum.fromValue(n.channel().name()),
                n.recipientRef(),
                n.message(),
                n.deliveredAt());
    }
}
