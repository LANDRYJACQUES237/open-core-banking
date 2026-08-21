package com.ocb.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La contre-passation, c'est-a-dire la seule maniere de corriger un grand livre immuable.
 *
 * <p>C'est aussi la brique sur laquelle reposera la compensation de la saga de
 * decaissement en Phase 4 : quand l'operateur refuse definitivement un paiement, les
 * fonds engages doivent revenir au portefeuille du client. Les proprietes verifiees ici
 * sont exactement celles dont cette saga aura besoin — notamment le fait que rejouer la
 * compensation ne rembourse pas deux fois.
 */
class ReversalIT extends LedgerIntegrationTestBase {

    @Test
    @DisplayName("contre-passer ramene le solde a son etat initial, au centime pres")
    void reversalRestoresTheInitialBalance() {
        String wallet = openWallet("reversal");
        BigDecimal feeBefore = balanceOf("4100");

        // Engagement de fonds, etape 1 d'un decaissement.
        ApiResponse original = postEntry("disb-" + suffix, "engagement decaissement",
                line(wallet, "DR", "5050"),
                line("1900", "CR", "5000"),
                line("4100", "CR", "50"));
        assertThat(original.status()).isEqualTo(201);

        // Le portefeuille etait vide : le debiter le met a decouvert. Le grand livre
        // enregistre ce qui s'est passe ; interdire le decouvert est une regle du
        // service de paiement, pas de la comptabilite.
        assertThat(balanceOf(wallet)).isEqualByComparingTo("-5050");
        assertThat(balanceOf("4100").subtract(feeBefore)).isEqualByComparingTo("50");

        ApiResponse reversal = reverse(original.entryRef(), "rev-" + suffix,
                "operateur a refuse definitivement");
        assertThat(reversal.status()).isEqualTo(201);

        assertThat(balanceOf(wallet)).isEqualByComparingTo("0");
        assertThat(balanceOf("4100").subtract(feeBefore))
                .as("les frais aussi sont annules")
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("l'ecriture d'origine reste intacte et porte un renvoi vers sa contre-passation")
    void originalIsPreservedAndLinked() {
        String wallet = openWallet("linked");
        ApiResponse original = postEntry("linked-" + suffix, "encaissement",
                line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

        ApiResponse reversal = reverse(original.entryRef(), "linked-rev-" + suffix, "erreur de saisie");

        ApiResponse originalAfter = get("/v1/journal-entries/" + original.entryRef());
        assertThat(originalAfter.body().get("description").asText()).isEqualTo("encaissement");
        assertThat(originalAfter.body().get("reversedByEntryRef").asText())
                .isEqualTo(reversal.entryRef());

        ApiResponse reversalEntry = get("/v1/journal-entries/" + reversal.entryRef());
        assertThat(reversalEntry.body().get("reversesEntryRef").asText())
                .isEqualTo(original.entryRef());
        // Les sens sont inverses, les montants inchanges.
        assertThat(reversalEntry.body().get("lines").get(0).get("direction").asText()).isEqualTo("CR");
        assertThat(reversalEntry.body().get("lines").get(0).get("amount").asText()).isEqualTo("1000");
    }

    @Test
    @DisplayName("rejouer la compensation avec la meme cle ne rembourse pas deux fois")
    void replayingTheCompensationIsSafe() {
        String wallet = openWallet("replayrev");
        ApiResponse original = postEntry("replayrev-" + suffix, "encaissement",
                line("1100", "DR", "3000"), line(wallet, "CR", "3000"));

        String key = "replayrev-key-" + suffix;
        ApiResponse first = reverse(original.entryRef(), key, "annulation");
        ApiResponse second = reverse(original.entryRef(), key, "annulation");
        ApiResponse third = reverse(original.entryRef(), key, "annulation");

        assertThat(first.status()).isEqualTo(201);
        assertThat(second.status()).isEqualTo(200);
        assertThat(third.status()).isEqualTo(200);
        assertThat(second.entryRef()).isEqualTo(first.entryRef());
        assertThat(third.entryRef()).isEqualTo(first.entryRef());

        // Le point qui compte pour une saga : trois compensations, un seul remboursement.
        assertThat(balanceOf(wallet)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("une seconde contre-passation avec une autre cle est refusee")
    void secondReversalWithAnotherKeyIsRejected() {
        String wallet = openWallet("double");
        ApiResponse original = postEntry("double-" + suffix, "encaissement",
                line("1100", "DR", "2000"), line(wallet, "CR", "2000"));

        assertThat(reverse(original.entryRef(), "double-rev1-" + suffix, "premiere").status())
                .isEqualTo(201);

        ApiResponse second = reverse(original.entryRef(), "double-rev2-" + suffix, "seconde");

        assertThat(second.status()).isEqualTo(422);
        assertThat(second.code()).isEqualTo("LEDGER_ALREADY_REVERSED");
        assertThat(balanceOf(wallet)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("contre-passer une contre-passation est refuse")
    void reversalOfAReversalIsRejected() {
        String wallet = openWallet("chain");
        ApiResponse original = postEntry("chain-" + suffix, "encaissement",
                line("1100", "DR", "500"), line(wallet, "CR", "500"));
        ApiResponse reversal = reverse(original.entryRef(), "chain-rev-" + suffix, "annulation");

        ApiResponse attempt = reverse(reversal.entryRef(), "chain-rev2-" + suffix, "re-annulation");

        assertThat(attempt.status()).isEqualTo(422);
        assertThat(attempt.code()).isEqualTo("LEDGER_CANNOT_REVERSE_REVERSAL");
    }

    @Test
    @DisplayName("contre-passer une ecriture inexistante produit un 404")
    void unknownEntry() {
        ApiResponse response = reverse("JE-INTROUVABLE", "unknown-" + suffix, "peu importe");
        assertThat(response.status()).isEqualTo(404);
        assertThat(response.code()).isEqualTo("LEDGER_ENTRY_NOT_FOUND");
    }

    private ApiResponse reverse(String entryRef, String idempotencyKey, String reason) {
        return post("/v1/journal-entries/%s/reversal".formatted(entryRef), idempotencyKey, """
                {"reason":"%s"}
                """.formatted(reason));
    }
}
