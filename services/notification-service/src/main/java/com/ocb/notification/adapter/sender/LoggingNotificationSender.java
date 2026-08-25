package com.ocb.notification.adapter.sender;

import com.ocb.notification.domain.Notification;
import com.ocb.notification.domain.port.NotificationSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Remise par journalisation.
 *
 * <p>Aucune passerelle SMS n'est branchee : ce service doit demontrer la consommation
 * idempotente et la mise au rebut, et un compte operateur n'ajouterait rien a cette
 * demonstration tout en rendant le service intestable.
 *
 * <p><b>Le message n'est pas journalise.</b> Il est deja conserve en base, ou il est
 * consultable et immuable ; le repeter dans les journaux le disperserait vers des systemes
 * d'agregation dont la duree de retention et les droits d'acces ne sont pas ceux de la
 * base. Un montant est une donnee personnelle des lors qu'il est rattachable a un compte.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void send(Notification notification) {
        log.info("Notification {} remise sur le canal {} pour la transaction {}",
                notification.type(), notification.channel(), notification.transactionId());
    }
}
