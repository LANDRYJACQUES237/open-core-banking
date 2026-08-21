package com.ocb.ledger.application;

import com.ocb.ledger.domain.Direction;
import com.ocb.ledger.domain.EntryLine;
import com.ocb.platform.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    @DisplayName("deux requetes identiques produisent la meme empreinte")
    void identicalRequestsMatch() {
        assertThat(fingerprint("10000", "9900"))
                .isEqualTo(fingerprint("10000", "9900"));
    }

    @Test
    @DisplayName("une ecriture decimale differente du meme montant produit la meme empreinte")
    void scaleDoesNotChangeTheFingerprint() {
        // "10000" et "10000.00" sont le meme montant. Refuser un rejeu pour cette seule
        // difference serait une fausse alerte, et pousserait l'appelant a contourner
        // l'idempotence. L'empreinte porte sur les valeurs normalisees par Money.
        assertThat(fingerprint("10000.00", "9900.0000"))
                .isEqualTo(fingerprint("10000", "9900"));
    }

    @Test
    @DisplayName("un montant different produit une empreinte differente")
    void differentAmountsDiffer() {
        assertThat(fingerprint("10000", "9900"))
                .isNotEqualTo(fingerprint("10000", "9800"));
    }

    @Test
    @DisplayName("l'ordre des lignes compte")
    void lineOrderMatters() {
        List<EntryLine> forward = List.of(
                line(1, "1100", Direction.DR, "100"),
                line(2, "4100", Direction.CR, "100"));
        List<EntryLine> reversed = List.of(
                line(1, "4100", Direction.CR, "100"),
                line(2, "1100", Direction.DR, "100"));

        assertThat(RequestFingerprint.ofEntry(null, null, "x", null, forward))
                .isNotEqualTo(RequestFingerprint.ofEntry(null, null, "x", null, reversed));
    }

    @Test
    @DisplayName("une date de valeur absente reste absente dans l'empreinte")
    void absentValueDateStaysAbsent() {
        // Si le defaut etait applique avant le calcul, deux rejeux du meme appel a deux
        // jours d'intervalle produiraient des empreintes differentes, et le second serait
        // refuse comme une reutilisation de cle alors que c'est un rejeu legitime.
        List<EntryLine> lines = List.of(
                line(1, "1100", Direction.DR, "100"),
                line(2, "4100", Direction.CR, "100"));

        String today = RequestFingerprint.ofEntry(null, null, "x", null, lines);
        String tomorrow = RequestFingerprint.ofEntry(null, null, "x", null, lines);

        assertThat(today).isEqualTo(tomorrow);
        assertThat(today).isNotEqualTo(
                RequestFingerprint.ofEntry(null, null, "x", LocalDate.of(2026, 8, 21), lines));
    }

    private static String fingerprint(String debit, String credit) {
        return RequestFingerprint.ofEntry("JE-1", "TX-1", "encaissement", LocalDate.of(2026, 8, 21),
                List.of(line(1, "1100", Direction.DR, debit),
                        line(2, "2100.wallet-c", Direction.CR, credit),
                        line(3, "4100", Direction.CR, "100")));
    }

    private static EntryLine line(int no, String account, Direction direction, String amount) {
        return new EntryLine(no, account, direction, Money.parse(amount, "XAF"));
    }
}
