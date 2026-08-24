package com.ocb.provider.application;

import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.ProviderOperation;
import com.ocb.provider.domain.port.AuditStore;
import com.ocb.provider.domain.port.CallbackStore;
import com.ocb.provider.domain.port.OperationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * Traitement d'un rappel dont la signature a deja ete verifiee par le filtre.
 *
 * <p>Deux protections successives, contre deux problemes differents.
 *
 * <p><b>La deduplication par identifiant de rappel</b> arrete le meme message reemis. Les
 * operateurs rejouent leurs rappels — parfois des heures plus tard, parfois en rafale
 * quand leur file se debloque.
 *
 * <p><b>Le statut definitif de l'operation</b> arrete le rappel tardif : un message
 * distinct, portant un identifiant nouveau, qui confirme une operation deja tranchee par
 * la relance de statut. La deduplication ne le voit pas passer.
 *
 * <p>Dans les deux cas, la reponse HTTP reste un succes. Repondre en erreur declencherait
 * des retentatives de l'operateur sur une operation close, c'est-a-dire une tempete
 * d'appels sans objet.
 */
@Service
public class CallbackService {

    private static final Logger log = LoggerFactory.getLogger(CallbackService.class);

    private final CallbackStore callbacks;
    private final OperationStore operations;
    private final OperationEventPublisher events;
    private final AuditStore audit;

    public CallbackService(CallbackStore callbacks,
                           OperationStore operations,
                           OperationEventPublisher events,
                           AuditStore audit) {
        this.callbacks = callbacks;
        this.operations = operations;
        this.events = events;
        this.audit = audit;
    }

    @Transactional
    public Result process(ProviderCode providerCode,
                          String providerEventId,
                          String externalRef,
                          String providerRef,
                          String status,
                          String fee,
                          String currency,
                          String errorCode,
                          String errorMessage,
                          String rawBody,
                          String signature,
                          String correlationId) {

        Optional<ProviderOperation> found = operations.findByExternalRef(externalRef);

        boolean fresh = callbacks.record(providerCode, providerEventId, externalRef,
                found.map(ProviderOperation::transactionId).orElse(null),
                signature, true, rawBody);

        if (!fresh) {
            log.debug("Rappel {} deja recu, acquitte sans effet", providerEventId);
            return new Result(true, true);
        }

        if (found.isEmpty()) {
            // Rappel pour une reference inconnue. Peut arriver si l'operateur repond a une
            // demande dont l'emission n'a pas ete enregistree — donc precisement le cas ou
            // l'appel initial avait expire. Le message brut est conserve, la reconciliation
            // s'en servira.
            log.warn("Rappel {} pour une reference inconnue : {}", providerEventId, externalRef);
            audit.append("CALLBACK_ORPHAN", "ProviderCallback", externalRef, correlationId,
                    Map.of("provider", providerCode.name(), "eventId", providerEventId));
            return new Result(true, false);
        }

        ProviderOperation locked = operations.lockById(found.get().id()).orElseThrow();

        if (locked.isFinal()) {
            // Rappel tardif : la relance de statut avait deja tranche. On acquitte, on
            // journalise, et surtout on ne republie rien — le moteur de paiement a deja
            // conclu et sa machine a etats refuserait de toute facon.
            log.info("Rappel tardif sur l'operation {} deja {}", locked.id(), locked.status());
            audit.append("CALLBACK_LATE", "ProviderOperation", locked.transactionId().toString(),
                    correlationId, Map.of("status", locked.status().name(), "eventId", providerEventId));
            callbacks.markProcessed(providerCode, providerEventId);
            return new Result(true, false);
        }

        switch (status) {
            case "SUCCEEDED" -> {
                Money providerFee = fee == null ? null : Money.parse(fee,
                        currency == null ? locked.amount().currencyCode() : currency);
                ProviderOperation resolved = operations.markResolved(locked.id(),
                        OperationStatus.SUCCEEDED, providerRef != null ? providerRef : locked.providerRef(),
                        providerFee, null, null);
                events.succeeded(resolved, "CALLBACK", correlationId);
            }
            case "FAILED" -> {
                ProviderOperation resolved = operations.markResolved(locked.id(),
                        OperationStatus.FAILED, providerRef != null ? providerRef : locked.providerRef(),
                        null, errorCode, errorMessage);
                events.failed(resolved, "CALLBACK", correlationId);
            }
            default ->
                // L'operateur signale une progression sans conclure. Rien a publier : le
                // moteur de paiement sait deja que l'operation est en cours, et la relance
                // de statut reste programmee.
                    log.debug("Rappel non conclusif ({}) sur l'operation {}", status, locked.id());
        }

        callbacks.markProcessed(providerCode, providerEventId);
        return new Result(true, false);
    }

    public record Result(boolean received, boolean duplicate) {
    }
}
