package com.ocb.notification.application;

import com.ocb.notification.domain.NotificationChannel;
import com.ocb.notification.domain.NotificationType;

/**
 * Redaction des messages.
 *
 * <p>Fonction pure, sans dependance : le texte adresse a un client merite d'etre eprouve
 * cas par cas, et il l'est en millisecondes.
 *
 * <p>Deux regles gouvernent ces textes.
 *
 * <p><b>Aucun identifiant technique.</b> Ni identifiant de transaction, ni reference
 * d'ecriture comptable, ni code d'erreur d'operateur. Ce qui n'a pas de sens pour le
 * destinataire n'a rien a faire dans un message qu'il va lire — et un identifiant expose
 * est une surface d'attaque de plus.
 *
 * <p><b>Un echec dit ce qui est arrive a l'argent.</b> "Votre operation a echoue" laisse
 * le client chercher son argent. Un decaissement compense doit dire que le montant a ete
 * rendu, sinon la compensation n'a servi qu'a equilibrer un bilan.
 */
public final class NotificationComposer {

    private NotificationComposer() {
    }

    public static String compose(NotificationType type, String amount, String currency,
                                 String reference) {
        return switch (type) {
            case COLLECTION_COMPLETED ->
                    "Vous avez recu %s %s.".formatted(amount, currency);

            case COLLECTION_FAILED ->
                    ("Votre encaissement de %s %s n'a pas abouti. Aucun montant n'a ete "
                            + "debite.").formatted(amount, currency);

            case DISBURSEMENT_COMPLETED ->
                    "%s %s ont ete envoyes depuis votre compte.".formatted(amount, currency);

            // Le message le plus important du service. Le client a vu son portefeuille
            // debite : ne lui annoncer qu'un echec le laisserait chercher son argent.
            case DISBURSEMENT_REVERSED ->
                    ("Votre envoi de %s %s n'a pas abouti. Le montant, frais compris, a ete "
                            + "recredite sur votre compte.").formatted(amount, currency);

            case TRANSFER_COMPLETED ->
                    "Votre transfert de %s %s a ete effectue.".formatted(amount, currency);

            // Message interne : la reference y est utile, contrairement aux precedents,
            // parce que son destinataire est celui qui va ouvrir le dossier.
            case MANUAL_REVIEW_REQUIRED ->
                    ("Transaction %s en attente d'arbitrage : aucune issue n'a pu etre "
                            + "etablie automatiquement.").formatted(reference);
        };
    }

    /**
     * Le canal decoule du type, il n'est pas choisi a l'appel.
     *
     * <p>Laisser l'appelant decider ouvrirait la porte a ce qu'un jour une revue manuelle
     * parte vers un client, par simple inattention dans une nouvelle branche de code.
     */
    public static NotificationChannel channelFor(NotificationType type) {
        return type == NotificationType.MANUAL_REVIEW_REQUIRED
                ? NotificationChannel.OPS
                : NotificationChannel.CUSTOMER;
    }
}
