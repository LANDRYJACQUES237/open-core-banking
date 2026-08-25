package com.ocb.notification.domain.port;

import com.ocb.notification.domain.Notification;

/**
 * Remise effective d'un message.
 *
 * <p><b>Aucun envoi reel n'est branche, et c'est un choix.</b> Ce que ce service doit
 * demontrer est la consommation idempotente et la mise au rebut ; une passerelle SMS
 * n'ajouterait rien a cette demonstration et rendrait le service intestable sans compte
 * operateur.
 *
 * <p><b>La consequence si on en branchait une.</b> Un envoi reel est un appel reseau : il
 * reintroduirait exactement la double ecriture que l'outbox resout ailleurs sur la
 * plateforme — le message part, la transaction locale echoue, et l'on ne sait plus si le
 * client a ete prevenu. Il faudrait alors enregistrer l'intention dans la transaction et
 * confier la remise a un relais, comme pour les evenements. L'implementation actuelle
 * echappe a ce probleme parce qu'elle n'appelle personne.
 */
public interface NotificationSender {

    void send(Notification notification);
}
