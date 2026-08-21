package com.ocb.ledger.domain;

import java.util.List;

/** Page de releve : les mouvements demandes, et de quoi situer la page dans l'ensemble. */
public record Statement(
        String accountNumber,
        int page,
        int size,
        long totalElements,
        List<StatementEntry> entries
) {

    public Statement {
        entries = List.copyOf(entries);
    }
}
