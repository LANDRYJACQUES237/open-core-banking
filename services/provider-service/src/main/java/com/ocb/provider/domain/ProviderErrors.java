package com.ocb.provider.domain;

/** Codes d'erreur stables. */
public final class ProviderErrors {

    private ProviderErrors() {
    }

    public static final String OPERATION_NOT_FOUND = "PROVIDER_OPERATION_NOT_FOUND";
    public static final String INVALID_SIGNATURE = "PROVIDER_INVALID_SIGNATURE";
    public static final String SIGNATURE_EXPIRED = "PROVIDER_SIGNATURE_EXPIRED";
    public static final String UNKNOWN_PROVIDER = "PROVIDER_UNKNOWN";
}
