package com.ocb.provider.adapter.web;

import com.ocb.platform.domain.error.ResourceNotFoundException;
import com.ocb.provider.api.OperationsApi;
import com.ocb.provider.api.model.OperationStatus;
import com.ocb.provider.api.model.ProviderOperation;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.ProviderErrors;
import com.ocb.provider.domain.port.OperationStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

/**
 * Consultation d'une operation, pour le diagnostic.
 *
 * <p>Repond a la question qu'on se pose reellement pendant un incident : ou en est cette
 * demande, combien de fois a-t-elle ete relancee, et depuis quand attend-on.
 */
@RestController
public class OperationsController implements OperationsApi {

    private final OperationStore operations;

    public OperationsController(OperationStore operations) {
        this.operations = operations;
    }

    @Override
    public ResponseEntity<ProviderOperation> getOperation(UUID transactionId) {
        com.ocb.provider.domain.ProviderOperation found = Arrays.stream(ProviderCode.values())
                .map(code -> operations.findByTransaction(code, transactionId))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        ProviderErrors.OPERATION_NOT_FOUND,
                        "Aucune operation pour la transaction %s".formatted(transactionId)));

        ProviderOperation api = new ProviderOperation(
                found.transactionId(),
                found.providerCode().name(),
                OperationStatus.fromValue(found.status().name()),
                found.attemptCount(),
                found.pollAttempts(),
                at(found.createdAt()),
                at(found.updatedAt()));
        api.setExternalRef(found.externalRef());
        api.setProviderRef(found.providerRef());
        api.setNextPollAt(at(found.nextPollAt()));
        api.setPollBudgetExhausted(found.pollBudgetExhausted());
        api.setLastError(found.lastError());
        return ResponseEntity.ok(api);
    }

    private OffsetDateTime at(java.time.Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
