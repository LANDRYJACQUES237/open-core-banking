package com.ocb.notification.domain;

/**
 * A qui le message s'adresse.
 *
 * <p>Tout evenement ne merite pas d'etre dit au client. La distinction n'est pas
 * cosmetique : elle evite d'inquieter le porteur du compte avec un incident qu'il ne peut
 * pas resoudre, et elle evite qu'un incident interne se perde parmi des messages
 * commerciaux.
 */
public enum NotificationChannel {
    CUSTOMER,
    OPS
}
