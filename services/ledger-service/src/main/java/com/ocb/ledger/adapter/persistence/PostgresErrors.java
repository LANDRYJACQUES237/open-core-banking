package com.ocb.ledger.adapter.persistence;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;

/**
 * Extrait de PostgreSQL l'information dont l'adaptateur a besoin pour reagir.
 *
 * <p>Spring traduit toutes les violations d'unicite en une meme
 * {@code DuplicateKeyException}, ce qui ne suffit pas ici : selon la contrainte violee,
 * la reponse correcte est un rejeu idempotent (200), un conflit (409) ou une violation
 * de regle metier (422). Il faut donc redescendre au message du serveur pour savoir
 * laquelle a saute.
 *
 * <p>C'est un couplage assume au SGBD. Ce service ne pretend pas etre portable : il
 * repose deja sur une contrainte differee, une colonne generee et une colonne identite.
 */
final class PostgresErrors {

    /** feature_not_supported : code retourne par le trigger d'immuabilite. */
    static final String IMMUTABLE_SQLSTATE = "0A000";

    /** check_violation : code retourne par la contrainte d'equilibre differee. */
    static final String CHECK_VIOLATION_SQLSTATE = "23514";

    private PostgresErrors() {
    }

    static String constraintName(DataAccessException e) {
        ServerErrorMessage message = serverMessage(e);
        return message == null ? null : message.getConstraint();
    }

    static String sqlState(DataAccessException e) {
        ServerErrorMessage message = serverMessage(e);
        return message == null ? null : message.getSQLState();
    }

    /**
     * Message brut leve par une fonction plpgsql.
     *
     * <p>Les migrations V2 et V3 prefixent leurs messages d'un code stable
     * ({@code LEDGER_UNBALANCED}, {@code LEDGER_MIXED_CURRENCY}, {@code LEDGER_IMMUTABLE})
     * precisement pour que ce texte soit exploitable ici sans analyse fragile.
     */
    static String rawMessage(DataAccessException e) {
        ServerErrorMessage message = serverMessage(e);
        return message == null ? e.getMostSpecificCause().getMessage() : message.getMessage();
    }

    static boolean isDeferredBalanceViolation(DataAccessException e) {
        String state = sqlState(e);
        String message = rawMessage(e);
        return CHECK_VIOLATION_SQLSTATE.equals(state)
                && message != null
                && (message.startsWith("LEDGER_UNBALANCED") || message.startsWith("LEDGER_MIXED_CURRENCY"));
    }

    private static ServerErrorMessage serverMessage(DataAccessException e) {
        Throwable cause = e.getMostSpecificCause();
        return cause instanceof PSQLException psql ? psql.getServerErrorMessage() : null;
    }
}
