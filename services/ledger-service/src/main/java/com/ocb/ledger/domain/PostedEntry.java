package com.ocb.ledger.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Ecriture enregistree. Immuable, par construction ici et par trigger en base.
 *
 * <p>{@code entrySeq} est le numero d'ordre attribue par la base. Il sert de repere
 * stable pour les instantanes de solde et pour la pagination du releve : contrairement
 * a {@code postedAt}, il est strictement croissant et ne peut pas produire d'ex aequo.
 */
public record PostedEntry(
        UUID id,
        String entryRef,
        long entrySeq,
        String transactionRef,
        String description,
        LocalDate valueDate,
        OffsetDateTime postedAt,
        String reversesEntryRef,
        String reversedByEntryRef,
        List<EntryLine> lines,
        String requestFingerprint
) {

    public PostedEntry {
        lines = List.copyOf(lines);
    }

    public boolean isReversal() {
        return reversesEntryRef != null;
    }

    public boolean isReversed() {
        return reversedByEntryRef != null;
    }
}
