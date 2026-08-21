package com.ocb.ledger.domain;

import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L'invariant de la partie double, verifie sur des ecritures generees plutot que choisies.
 *
 * <p>Un test par l'exemple prouve que les cas auxquels on a pense fonctionnent. Ces
 * proprietes cherchent les cas auxquels on n'a pas pense : des repartitions inegales,
 * beaucoup de lignes, de tres gros montants. C'est la que se logent les erreurs de
 * signe et de debordement dans un systeme comptable.
 */
class DoubleEntryPropertyTest {

    /**
     * Propriete centrale : toute ecriture acceptee par le domaine est equilibree.
     * Formulee ainsi, elle est verifiable sans connaitre la construction interne.
     */
    @Property(tries = 500)
    void everyAcceptedEntryIsBalanced(@ForAll("balancedEntries") JournalEntryDraft entry) {
        assertThat(entry.totalDebit()).isEqualTo(entry.totalCredit());
    }

    @Property(tries = 500)
    void reversingAnAcceptedEntryYieldsAnAcceptedEntry(@ForAll("balancedEntries") JournalEntryDraft entry) {
        JournalEntryDraft reversal = entry.reversal("JE-REV", "propriete");
        assertThat(reversal.totalDebit()).isEqualTo(entry.totalCredit());
        assertThat(reversal.totalCredit()).isEqualTo(entry.totalDebit());
    }

    /**
     * Propriete miroir : deplacer une unite d'un cote suffit a faire refuser l'ecriture.
     * Sans elle, un domaine qui accepterait tout passerait la propriete precedente.
     */
    @Property(tries = 300)
    void shiftingASingleUnitBreaksTheEntry(@ForAll("balancedEntries") JournalEntryDraft entry) {
        List<EntryLine> tampered = new ArrayList<>(entry.lines());
        EntryLine first = tampered.getFirst();
        tampered.set(0, new EntryLine(
                first.lineNo(), first.accountNumber(), first.direction(),
                first.amount().add(Money.parse("1", "XAF"))));

        assertThatThrownBy(() -> new JournalEntryDraft(
                "JE-X", null, "altere", LocalDate.now(), tampered))
                .isInstanceOf(InvariantViolationException.class);
    }

    @Property(tries = 300)
    void anyBalancedSplitIsAccepted(@ForAll @IntRange(min = 2, max = 20) int parts,
                                    @ForAll @IntRange(min = 1, max = 1_000_000) int unitAmount) {
        // Un debit unique reparti en N credits egaux : configuration courante d'un
        // reglement de masse, et cas ou une division mal arrondie ferait deriver le total.
        long total = (long) parts * unitAmount;

        List<EntryLine> lines = new ArrayList<>();
        lines.add(new EntryLine(1, "1100", Direction.DR, xaf(total)));
        for (int i = 0; i < parts; i++) {
            lines.add(new EntryLine(i + 2, "2100.w" + i, Direction.CR, xaf(unitAmount)));
        }

        JournalEntryDraft entry = new JournalEntryDraft("JE-SPLIT", null, "repartition",
                LocalDate.now(), lines);

        assertThat(entry.totalDebit()).isEqualTo(entry.totalCredit());
        assertThat(entry.totalDebit()).isEqualTo(xaf(total));
    }

    @Provide
    Arbitrary<JournalEntryDraft> balancedEntries() {
        return Arbitraries.integers().between(1, 1_000_000_000)
                .list().ofMinSize(1).ofMaxSize(8)
                .flatMap(debitAmounts -> Arbitraries.integers().between(1, debitAmounts.size())
                        .map(creditCount -> buildBalanced(debitAmounts, creditCount)));
    }

    /**
     * Construit une ecriture equilibree : N debits generes, puis le meme total reparti
     * sur M credits. Le reste de la division entiere est ajoute au dernier credit, ce
     * qui garantit l'egalite exacte quelles que soient les valeurs tirees.
     */
    private static JournalEntryDraft buildBalanced(List<Integer> debitAmounts, int creditCount) {
        long total = debitAmounts.stream().mapToLong(Integer::longValue).sum();

        List<EntryLine> lines = new ArrayList<>();
        int no = 1;
        for (Integer amount : debitAmounts) {
            lines.add(new EntryLine(no++, "1100", Direction.DR, xaf(amount)));
        }

        long share = total / creditCount;
        long remainder = total - share * creditCount;
        for (int i = 0; i < creditCount; i++) {
            long amount = share + (i == creditCount - 1 ? remainder : 0);
            if (amount == 0) {
                // Une ligne nulle serait refusee : on la reporte sur la precedente
                // plutot que de generer une ecriture invalide.
                continue;
            }
            lines.add(new EntryLine(no++, "2100.w" + i, Direction.CR, xaf(amount)));
        }

        return new JournalEntryDraft("JE-GEN", null, "genere", LocalDate.now(), lines);
    }

    private static Money xaf(long amount) {
        return Money.of(BigDecimal.valueOf(amount), "XAF");
    }
}
