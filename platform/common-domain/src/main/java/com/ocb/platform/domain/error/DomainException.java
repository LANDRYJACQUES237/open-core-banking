package com.ocb.platform.domain.error;

/**
 * Racine des erreurs metier.
 *
 * <p>Chaque exception porte un {@code code} stable, destine au code appelant, distinct
 * du message destine a un humain. Un client peut brancher sur le code sans parser
 * un message susceptible de changer a la prochaine relecture.
 *
 * <p>Cette hierarchie ne connait pas HTTP. La traduction en statut est faite une seule
 * fois, dans {@code common-web}, ce qui permet de reutiliser le domaine derriere un
 * consommateur Kafka ou un batch sans trainer une dependance web.
 */
public abstract class DomainException extends RuntimeException {

    private final String code;

    protected DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected DomainException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
