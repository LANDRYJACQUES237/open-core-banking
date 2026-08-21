package com.ocb.ledger.domain;

import com.ocb.platform.domain.money.Money;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Une ligne de releve : un mouvement, plus le solde du compte immediatement apres. */
public record StatementEntry(
        String entryRef,
        long entrySeq,
        OffsetDateTime postedAt,
        LocalDate valueDate,
        String description,
        String transactionRef,
        Direction direction,
        Money amount,
        Money runningBalance
) {
}
