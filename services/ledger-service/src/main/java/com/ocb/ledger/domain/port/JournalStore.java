package com.ocb.ledger.domain.port;

import com.ocb.ledger.domain.JournalEntryDraft;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.PostedEntry;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port de persistance des ecritures. Insertion et lecture seulement : rien n'y est modifiable. */
public interface JournalStore {

    /**
     * Enregistre une ecriture.
     *
     * <p>L'implementation doit garantir qu'une meme {@code idempotencyKey} ne produit
     * jamais deux ecritures, y compris sous appels concurrents. Un rejeu retourne
     * l'ecriture existante avec {@code created = false}.
     *
     * @param accounts        comptes deja resolus et valides par la couche application,
     *                        indexes par numero de compte. Les passer plutot que de les
     *                        relire ici evite une seconde lecture, et surtout evite que
     *                        la validation metier (compte gele, compte de regroupement,
     *                        devise) se retrouve dupliquee dans du SQL ou elle echapperait
     *                        aux tests unitaires
     * @param reversesEntryId identifiant de l'ecriture contre-passee, ou {@code null}
     */
    Posted post(JournalEntryDraft draft,
                Map<String, LedgerAccount> accounts,
                String idempotencyKey,
                String requestFingerprint,
                UUID reversesEntryId,
                String correlationId);

    Optional<PostedEntry> findByRef(String entryRef);

    Optional<PostedEntry> findByIdempotencyKey(String idempotencyKey);

    /** Retourne la contre-passation d'une ecriture, si elle existe. */
    Optional<PostedEntry> findReversalOf(UUID reversedEntryId);

    boolean entryRefExists(String entryRef);

    record Posted(PostedEntry entry, boolean created) {
    }
}
