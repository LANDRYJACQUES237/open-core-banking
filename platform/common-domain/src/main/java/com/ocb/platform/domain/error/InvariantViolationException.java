package com.ocb.platform.domain.error;

/**
 * Une regle metier refuse la requete. La requete est syntaxiquement valide,
 * mais l'operation demandee violerait un invariant du domaine.
 *
 * <p>Traduit en HTTP 422. C'est le cas d'une ecriture desequilibree, d'un montant
 * dont l'echelle ne respecte pas la devise, ou d'une contre-passation d'ecriture
 * deja contre-passee.
 */
public class InvariantViolationException extends DomainException {

    public InvariantViolationException(String code, String message) {
        super(code, message);
    }

    public InvariantViolationException(String code, String message, Throwable cause) {
        super(code, message, cause);
    }
}
