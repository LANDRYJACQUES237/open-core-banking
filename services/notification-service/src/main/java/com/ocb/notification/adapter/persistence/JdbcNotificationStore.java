package com.ocb.notification.adapter.persistence;

import com.ocb.notification.domain.Notification;
import com.ocb.notification.domain.NotificationChannel;
import com.ocb.notification.domain.NotificationType;
import com.ocb.notification.domain.port.NotificationStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcNotificationStore implements NotificationStore {

    private final JdbcClient jdbc;

    public JdbcNotificationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Notification n) {
        jdbc.sql("""
                        INSERT INTO notification.notification
                            (id, transaction_id, type, channel, recipient_ref, message,
                             correlation_id, delivered_at)
                        VALUES (:id, :transactionId, :type, :channel, :recipientRef, :message,
                                :correlationId, :deliveredAt)
                        """)
                .param("id", n.id())
                .param("transactionId", n.transactionId())
                .param("type", n.type().name())
                .param("channel", n.channel().name())
                .param("recipientRef", n.recipientRef())
                .param("message", n.message())
                .param("correlationId", n.correlationId())
                .param("deliveredAt", n.deliveredAt())
                .update();
    }

    @Override
    public List<Notification> findByTransaction(UUID transactionId) {
        return jdbc.sql("""
                        SELECT id, transaction_id, type, channel, recipient_ref, message,
                               correlation_id, delivered_at
                          FROM notification.notification
                         WHERE transaction_id = :id
                         ORDER BY seq
                        """)
                .param("id", transactionId)
                .query((rs, rowNum) -> new Notification(
                        rs.getObject("id", UUID.class),
                        rs.getObject("transaction_id", UUID.class),
                        NotificationType.valueOf(rs.getString("type")),
                        NotificationChannel.valueOf(rs.getString("channel")),
                        rs.getString("recipient_ref"),
                        rs.getString("message"),
                        rs.getString("correlation_id"),
                        rs.getObject("delivered_at", OffsetDateTime.class)))
                .list();
    }
}
