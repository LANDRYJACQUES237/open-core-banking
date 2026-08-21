package com.ocb.platform.domain.error;

/** La ressource designee n'existe pas. Traduit en HTTP 404. */
public class ResourceNotFoundException extends DomainException {

    public ResourceNotFoundException(String code, String message) {
        super(code, message);
    }
}
