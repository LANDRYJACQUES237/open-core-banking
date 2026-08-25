package com.ocb.notification.domain.port;

import com.ocb.notification.domain.Notification;

import java.util.List;
import java.util.UUID;

/**
 * Trace des messages emis.
 *
 * <p>Append-only, en droits comme en declencheurs. "Le client avait-il ete prevenu ?" est
 * une question qui se pose apres coup, souvent en litige : la reponse ne vaut que si
 * personne n'a pu la retoucher entre-temps.
 */
public interface NotificationStore {

    void record(Notification notification);

    List<Notification> findByTransaction(UUID transactionId);
}
