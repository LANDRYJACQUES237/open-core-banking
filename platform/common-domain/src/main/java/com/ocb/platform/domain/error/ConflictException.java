package com.ocb.platform.domain.error;

/**
 * L'operation entre en conflit avec l'etat courant de la ressource.
 * Traduit en HTTP 409.
 *
 * <p>Distinct de {@link InvariantViolationException} : un conflit peut disparaitre
 * si l'appelant reessaie plus tard, une violation d'invariant non.
 */
public class ConflictException extends DomainException {

    public ConflictException(String code, String message) {
        super(code, message);
    }

    public ConflictException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
