package com.ocb.ledger.domain;

import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryDraftTest {

    @Nested
    @DisplayName("Les trois flux du plan de comptes")
    class RealWorldFlows {

        /**
         * Encaissement de 10 000 XAF, frais plateforme 100, commission MTN 150.
         * Le client recoit 9 900, notre float est credite de 9 850 seulement,
         * la difference etant ce que l'operateur a preleve.
         */
        @Test
        @DisplayName("encaissement : float, commission operateur, portefeuille, commission plateforme")
        void collection() {
            JournalEntryDraft entry = draft(
                    line("1100", Direction.DR, "9850"),
                    line("5100", Direction.DR, "150"),
                    line("2100.wallet-c", Direction.CR, "9900"),
                    line("4100", Direction.CR, "100"));

            assertThat(entry.totalDebit()).isEqualTo(xaf("10000"));
            assertThat(entry.totalCredit()).isEqualTo(xaf("10000"));
        }

        @Test
        @DisplayName("decaissement, etape 1 : engagement des fonds vers le compte de passage")
        void disbursementReservation() {
            JournalEntryDraft entry = draft(
                    line("2100.wallet-c", Direction.DR, "5050"),
                    line("1900", Direction.CR, "5000"),
                    line("4100", Direction.CR, "50"));

            assertThat(entry.totalDebit()).isEqualTo(xaf("5050"));
            assertThat(entry.totalCredit()).isEqualTo(xaf("5050"));
        }

        @Test
        @DisplayName("decaissement, etape 2 : livraison, le passage se solde vers le float")
        void disbursementSettlement() {
            JournalEntryDraft entry = draft(
                    line("1900", Direction.DR, "5000"),
                    line("5100", Direction.DR, "25"),
                    line("1100", Direction.CR, "5025"));

            assertThat(entry.totalDebit()).isEqualTo(xaf("5025"));
        }

        /**
         * Le transfert entre deux portefeuilles est une seule ecriture equilibree.
         * Il ne traverse aucune frontiere de service, donc il n'appelle aucune saga :
         * une transaction ACID suffit, et en ajouter une serait de la mise en scene.
         */
        @Test
        @DisplayName("transfert portefeuille a portefeuille : une seule ecriture, aucune saga")
        void walletToWallet() {
            JournalEntryDraft entry = draft(
                    line("2100.wallet-a", Direction.DR, "2020"),
                    line("2100.wallet-b", Direction.CR, "2000"),
                    line("4100", Direction.CR, "20"));

            assertThat(entry.totalDebit()).isEqualTo(entry.totalCredit());
            assertThat(entry.lines()).hasSize(3);
        }
    }

    @Nested
    @DisplayName("Invariant de la partie double")
    class Balance {

        @Test
        @DisplayName("un ecart d'une unite suffit a refuser l'ecriture")
        void rejectsOffByOne() {
            assertThatThrownBy(() -> draft(
                    line("1100", Direction.DR, "10000"),
                    line("2100.wallet-c", Direction.CR, "9999")))
                    .isInstanceOf(InvariantViolationException.class)
                    .hasMessageContaining("desequilibree")
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(LedgerErrors.UNBALANCED_ENTRY);
        }

        @Test
        @DisplayName("aucune tolerance : un grand livre qui accepte un epsilon derive")
        void rejectsSmallestPossibleGap() {
            assertThatThrownBy(() -> draft(
                    line("1100", Direction.DR, "1"),
                    line("2100.wallet-c", Direction.CR, "2")))
                    .isInstanceOf(InvariantViolationException.class);
        }

        @Test
        @DisplayName("une ecriture a moins de deux lignes est refusee")
        void rejectsSingleLine() {
            assertThatThrownBy(() -> draft(line("1100", Direction.DR, "10000")))
                    .isInstanceOf(InvariantViolationException.class)
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(LedgerErrors.TOO_FEW_LINES);
        }

        @Test
        @DisplayName("un montant nul est refuse : une ligne qui ne deplace rien n'a pas de sens")
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> line("1100", Direction.DR, "0"))
                    .isInstanceOf(InvariantViolationException.class);
        }

        @Test
        @DisplayName("un montant negatif est refuse : le sens est porte par la direction")
        void rejectsNegativeAmount() {
            assertThatThrownBy(() -> new EntryLine(
                    1, "1100", Direction.DR, Money.of(new BigDecimal("-10"), "XAF")))
                    .isInstanceOf(InvariantViolationException.class);
        }

        @Test
        @DisplayName("melanger deux devises dans une ecriture est refuse")
        void rejectsMixedCurrency() {
            assertThatThrownBy(() -> draft(
                    new EntryLine(1, "1100", Direction.DR, Money.parse("100", "XAF")),
                    new EntryLine(2, "1200", Direction.CR, Money.parse("100", "EUR"))))
                    .isInstanceOf(InvariantViolationException.class)
                    .extracting(e -> ((InvariantViolationException) e).code())
                    .isEqualTo(LedgerErrors.MIXED_CURRENCY);
        }

        @Test
        @DisplayName("une ecriture a plusieurs lignes du meme cote reste valide si les totaux s'egalent")
        void acceptsManyToMany() {
            assertThatCode(() -> draft(
                    line("1100", Direction.DR, "600"),
                    line("1101", Direction.DR, "400"),
                    line("2100.wallet-a", Direction.CR, "700"),
                    line("2100.wallet-b", Direction.CR, "300")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Numerotation des lignes")
    class LineNumbering {

        @Test
        @DisplayName("les numeros sont attribues par le domaine, en sequence continue")
        void renumbersSequentially() {
            JournalEntryDraft entry = draft(
                    new EntryLine(99, "1100", Direction.DR, Money.parse("100", "XAF")),
                    new EntryLine(7, "4100", Direction.CR, Money.parse("100", "XAF")));

            assertThat(entry.lines()).extracting(EntryLine::lineNo).containsExactly(1, 2);
        }
    }

    @Nested
    @DisplayName("Contre-passation")
    class Reversal {

        @Test
        @DisplayName("chaque ligne change de sens, les montants sont inchanges")
        void invertsEveryDirection() {
            JournalEntryDraft original = draft(
                    line("2100.wallet-c", Direction.DR, "5050"),
                    line("1900", Direction.CR, "5000"),
                    line("4100", Direction.CR, "50"));

            JournalEntryDraft reversal = original.reversal("JE-REV-1", "operateur a refuse");

            assertThat(reversal.lines()).extracting(EntryLine::direction)
                    .containsExactly(Direction.CR, Direction.DR, Direction.DR);
            assertThat(reversal.lines()).extracting(l -> l.amount().toPlainString())
                    .containsExactly("5050", "5000", "50");
            assertThat(reversal.description()).contains("Contre-passation");
        }

        @Test
        @DisplayName("la contre-passation est equilibree par construction")
        void isBalancedByConstruction() {
            JournalEntryDraft original = draft(
                    line("1100", Direction.DR, "9850"),
                    line("5100", Direction.DR, "150"),
                    line("2100.wallet-c", Direction.CR, "9900"),
                    line("4100", Direction.CR, "100"));

            JournalEntryDraft reversal = original.reversal("JE-REV-2", "erreur de saisie");

            // Inverser toutes les directions preserve l'egalite des totaux : ce n'est pas
            // une chance, c'est une propriete. Le fait que le constructeur ne leve pas
            // suffit a la demontrer, mais on l'explicite.
            assertThat(reversal.totalDebit()).isEqualTo(original.totalCredit());
            assertThat(reversal.totalCredit()).isEqualTo(original.totalDebit());
        }

        @Test
        @DisplayName("contre-passer puis contre-passer a nouveau ramene aux sens d'origine")
        void doubleReversalRestoresDirections() {
            JournalEntryDraft original = draft(
                    line("1100", Direction.DR, "100"),
                    line("4100", Direction.CR, "100"));

            JournalEntryDraft twice = original.reversal("R1", "x").reversal("R2", "y");

            assertThat(twice.lines()).extracting(EntryLine::direction)
                    .containsExactly(Direction.DR, Direction.CR);
        }
    }

    // ---------------------------------------------------------------------------------

    private static JournalEntryDraft draft(EntryLine... lines) {
        return new JournalEntryDraft("JE-TEST", "TX-1", "test", LocalDate.of(2026, 8, 21), List.of(lines));
    }

    private static EntryLine line(String account, Direction direction, String amount) {
        return new EntryLine(1, account, direction, Money.parse(amount, "XAF"));
    }

    private static Money xaf(String amount) {
        return Money.parse(amount, "XAF");
    }
}
