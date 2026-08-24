package com.ocb.provider.domain.port;

import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.ProviderOperation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationStore {

    /**
     * Cree l'operation, ou rend celle qui existait deja.
     *
     * <p>L'unicite est portee par la base sur {@code (provider_code, transaction_id)}.
     * C'est le garde-fou ultime contre le double prelevement : meme si la commande Kafka
     * etait livree deux fois et que la deduplication echouait, aucune seconde operation
     * ne pourrait naitre.
     */
    Created createOrGet(ProviderOperation operation);

    Optional<ProviderOperation> findByTransaction(ProviderCode providerCode, UUID transactionId);

    Optional<ProviderOperation> findByExternalRef(String externalRef);

    /**
     * Charge en verrouillant la ligne jusqu'a la fin de la transaction courante.
     *
     * <p>C'est ce verrou qui rend correcte la course entre un rappel entrant et
     * l'ordonnanceur de relances. Les deux visent la meme operation et arrivent
     * regulierement en meme temps ; sans serialisation, tous deux liraient l'ancien etat
     * et tous deux publieraient une issue.
     */
    Optional<ProviderOperation> lockById(UUID id);

    /** Enregistre l'accuse de reception de l'operateur. */
    ProviderOperation markAccepted(UUID id, String providerRef, Instant nextPollAt);

    /** Enregistre une issue definitive. Annule les relances restantes. */
    ProviderOperation markResolved(UUID id, OperationStatus status, String providerRef,
                                   Money fee, String errorCode, String errorMessage);

    /** Enregistre une tentative sans reponse et programme la suivante. */
    ProviderOperation recordAttempt(UUID id, int pollAttempts, Instant lastPolledAt,
                                    Instant nextPollAt, String lastError);

    /** Abandonne les relances : le budget est epuise. */
    ProviderOperation markBudgetExhausted(UUID id, String lastError);

    /**
     * Operations dont la relance est due.
     *
     * <p>Verrouillees avec {@code SKIP LOCKED} : plusieurs instances de l'ordonnanceur
     * peuvent tourner sans se disputer les memes lignes. L'ordre n'a ici aucune
     * importance, contrairement a l'outbox — deux relances d'operations differentes sont
     * independantes.
     */
    List<ProviderOperation> lockDueForPolling(Instant now, int limit);

    /**
     * @param created faux si l'operation existait deja, ce qui signale une commande
     *                rejouee et doit empecher tout nouvel appel a l'operateur
     */
    record Created(ProviderOperation operation, boolean created) {
    }
}
