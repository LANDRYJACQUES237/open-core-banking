package com.ocb.ledger.domain;

import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * Ecriture candidate, validee mais pas encore enregistree.
 *
 * <p>C'est le seul endroit ou une ecriture peut naitre. Un objet de ce type qui existe
 * est necessairement equilibre : la verification a lieu dans le constructeur compact,
 * donc il n'y a pas d'etat intermediaire ou une ecriture invalide serait manipulable.
 * Le reste du code n'a jamais a se demander si l'ecriture qu'il tient est correcte.
 *
 * <p>Cette validation est doublee d'une contrainte differee en base (migration V3).
 * La redondance est deliberee : le controle applicatif protege un chemin d'ecriture,
 * la contrainte en protege tous les autres.
 */
public record JournalEntryDraft(
        String entryRef,
        String transactionRef,
        String description,
        LocalDate valueDate,
        List<EntryLine> lines
) {

    public static final int MIN_LINES = 2;
    public static final int MAX_LINES = 100;

    public JournalEntryDraft {
        Objects.requireNonNull(entryRef, "entryRef");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(lines, "lines");

        if (lines.size() < MIN_LINES) {
            throw new InvariantViolationException(
                    LedgerErrors.TOO_FEW_LINES,
                    ("Une ecriture comporte au moins %d lignes ; recu %d. "
                            + "En partie double, quelque chose vient toujours de quelque part")
                            .formatted(MIN_LINES, lines.size()));
        }
        if (lines.size() > MAX_LINES) {
            throw new InvariantViolationException(
                    LedgerErrors.TOO_MANY_LINES,
                    "Une ecriture comporte au plus %d lignes ; recu %d".formatted(MAX_LINES, lines.size()));
        }

        lines = renumber(lines);
        Currency currency = singleCurrency(lines);
        requireBalanced(lines, currency);
    }

    /**
     * Les numeros de ligne sont attribues par le domaine, pas par l'appelant.
     *
     * <p>Ils servent a rendre une ecriture reproductible a l'affichage et a l'audit ;
     * les laisser au choix du client ouvrirait la porte a des trous, des doublons et
     * des ordres instables entre deux relectures du meme releve.
     */
    private static List<EntryLine> renumber(List<EntryLine> lines) {
        List<EntryLine> numbered = new ArrayList<>(lines.size());
        int no = 1;
        for (EntryLine line : lines) {
            numbered.add(line.withLineNo(no++));
        }
        return List.copyOf(numbered);
    }

    private static Currency singleCurrency(List<EntryLine> lines) {
        Currency currency = lines.getFirst().amount().currency();
        for (EntryLine line : lines) {
            if (!line.amount().currency().equals(currency)) {
                throw new InvariantViolationException(
                        LedgerErrors.MIXED_CURRENCY,
                        ("Une ecriture ne peut pas melanger %s et %s : la v1 n'a pas de comptes "
                                + "de position de change, une telle ecriture serait desequilibree "
                                + "economiquement meme si elle s'annule arithmetiquement")
                                .formatted(currency.getCurrencyCode(),
                                        line.amount().currency().getCurrencyCode()));
            }
        }
        return currency;
    }

    /**
     * L'invariant de la partie double.
     *
     * <p>La comparaison porte sur des {@link Money}, donc sur des {@link java.math.BigDecimal}
     * d'echelle normalisee. Elle est exacte : aucune tolerance, aucun epsilon. Un ecart
     * d'un centime est un ecart, et un grand livre qui tolere un epsilon derive.
     */
    private static void requireBalanced(List<EntryLine> lines, Currency currency) {
        Money debits = Money.zero(currency);
        Money credits = Money.zero(currency);
        for (EntryLine line : lines) {
            if (line.isDebit()) {
                debits = debits.add(line.amount());
            } else {
                credits = credits.add(line.amount());
            }
        }
        if (!debits.equals(credits)) {
            throw new InvariantViolationException(
                    LedgerErrors.UNBALANCED_ENTRY,
                    "Ecriture desequilibree : debits %s, credits %s, ecart %s"
                            .formatted(debits, credits, debits.subtract(credits)));
        }
    }

    public Currency currency() {
        return lines.getFirst().amount().currency();
    }

    public Money totalDebit() {
        return lines.stream()
                .filter(EntryLine::isDebit)
                .map(EntryLine::amount)
                .reduce(Money.zero(currency()), Money::add);
    }

    public Money totalCredit() {
        return lines.stream()
                .filter(line -> !line.isDebit())
                .map(EntryLine::amount)
                .reduce(Money.zero(currency()), Money::add);
    }

    /**
     * Construit la contre-passation de cette ecriture.
     *
     * <p>Chaque ligne change de sens, les montants sont inchanges. L'ecriture obtenue
     * est equilibree par construction, puisque l'originale l'etait — inverser toutes
     * les directions preserve l'egalite des totaux.
     */
    public JournalEntryDraft reversal(String reversalEntryRef, String reason) {
        List<EntryLine> reversedLines = lines.stream().map(EntryLine::reversed).toList();
        return new JournalEntryDraft(
                reversalEntryRef,
                transactionRef,
                "Contre-passation de %s : %s".formatted(entryRef, reason),
                LocalDate.now(),
                reversedLines);
    }
}
