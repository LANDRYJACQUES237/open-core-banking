package com.ocb.platform.events;

/**
 * Types d'evenements et de commandes.
 *
 * <p>Chaque constante correspond a une entree {@code $defs} de
 * {@code contracts/events/payloads.schema.json}. La correspondance est verifiee par
 * {@code EventContractTest} : un type declare ici sans schema, ou un schema sans type,
 * fait echouer le build.
 */
public final class EventTypes {

    // --- Commandes vers l'operateur --------------------------------------------------
    public static final String PROVIDER_COLLECTION_EXECUTE = "provider.collection.execute";

    // --- Issues d'operations operateur -----------------------------------------------
    public static final String PROVIDER_OPERATION_ACCEPTED = "provider.operation.accepted";
    public static final String PROVIDER_OPERATION_SUCCEEDED = "provider.operation.succeeded";
    public static final String PROVIDER_OPERATION_FAILED = "provider.operation.failed";
    public static final String PROVIDER_OPERATION_UNRESOLVED = "provider.operation.unresolved";

    // --- Cycle de vie d'une transaction ----------------------------------------------
    public static final String PAYMENT_COLLECTION_REQUESTED = "payment.collection.requested";
    public static final String PAYMENT_COLLECTION_COMPLETED = "payment.collection.completed";
    public static final String PAYMENT_COLLECTION_FAILED = "payment.collection.failed";
    public static final String PAYMENT_MANUAL_REVIEW_REQUIRED = "payment.transaction.manual_review_required";

    private EventTypes() {
    }
}
