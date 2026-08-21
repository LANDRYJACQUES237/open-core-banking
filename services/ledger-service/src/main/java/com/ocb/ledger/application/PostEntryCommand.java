package com.ocb.ledger.application;

import com.ocb.ledger.domain.Direction;

import java.time.LocalDate;
import java.util.List;

/**
 * Demande d'enregistrement d'une ecriture, telle que recue.
 *
 * <p>Les champs optionnels ({@code entryRef}, {@code valueDate}) sont conserves tels quels,
 * defauts non appliques : l'empreinte d'idempotence doit refleter ce que l'appelant a
 * effectivement envoye, pas ce que le serveur en a deduit.
 */
public record PostEntryCommand(
        String entryRef,
        String transactionRef,
        String description,
        LocalDate valueDate,
        List<Line> lines,
        String idempotencyKey,
        String correlationId
) {

    public record Line(String accountNumber, Direction direction, String amount, String currency) {
    }
}
