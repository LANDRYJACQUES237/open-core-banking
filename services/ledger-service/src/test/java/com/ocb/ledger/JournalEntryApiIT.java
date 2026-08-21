package com.ocb.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class JournalEntryApiIT extends LedgerIntegrationTestBase {

    @Nested
    @DisplayName("Encaissement de bout en bout")
    class Collection {

        /**
         * Le scenario du plan de comptes : le client envoie 10 000 XAF, la plateforme
         * prend 100 de frais, MTN preleve 150 de commission. Le client recoit 9 900,
         * notre float n'est credite que de 9 850.
         */
        @Test
        @DisplayName("les quatre comptes bougent des montants attendus")
        void movesEveryAccountAsExpected() {
            String wallet = openWallet("collect");

            BigDecimal floatBefore = balanceOf("1100");
            BigDecimal feeBefore = balanceOf("4100");
            BigDecimal costBefore = balanceOf("5100");

            ApiResponse response = postEntry("collection-" + suffix, "encaissement MTN 10000",
                    line("1100", "DR", "9850"),
                    line("5100", "DR", "150"),
                    line(wallet, "CR", "9900"),
                    line("4100", "CR", "100"));

            assertThat(response.status()).as("%s", response.body()).isEqualTo(201);

            // Le portefeuille est neuf : son solde est verifiable en valeur absolue.
            // Un portefeuille client est un compte de passif, donc un credit de 9 900
            // produit un solde presente de +9 900 : la dette envers le client.
            assertThat(balanceOf(wallet)).isEqualByComparingTo("9900");

            // Les comptes du plan de comptes sont partages par toute la suite de tests :
            // on raisonne en variation, comme il faudrait le faire sur un grand livre reel.
            assertThat(balanceOf("1100").subtract(floatBefore)).isEqualByComparingTo("9850");
            assertThat(balanceOf("4100").subtract(feeBefore)).isEqualByComparingTo("100");
            assertThat(balanceOf("5100").subtract(costBefore)).isEqualByComparingTo("150");
        }

        @Test
        @DisplayName("le releve expose le solde progressif et la reference d'ecriture")
        void statementShowsRunningBalance() {
            String wallet = openWallet("statement");

            postEntry("stmt-1-" + suffix, "premier credit",
                    line("1100", "DR", "5000"), line(wallet, "CR", "5000"));
            postEntry("stmt-2-" + suffix, "second credit",
                    line("1100", "DR", "3000"), line(wallet, "CR", "3000"));
            postEntry("stmt-3-" + suffix, "un debit",
                    line(wallet, "DR", "2000"), line("1100", "CR", "2000"));

            ApiResponse statement = get("/v1/accounts/%s/entries?page=0&size=50".formatted(wallet));
            assertThat(statement.status()).isEqualTo(200);
            assertThat(statement.body().get("totalElements").asInt()).isEqualTo(3);

            var content = statement.body().get("content");
            // Ordre antichronologique : le mouvement le plus recent en tete.
            assertThat(content.get(0).get("direction").asText()).isEqualTo("DR");
            assertThat(content.get(0).get("amount").asText()).isEqualTo("2000");
            assertThat(content.get(0).get("runningBalance").asText()).isEqualTo("6000");
            assertThat(content.get(1).get("runningBalance").asText()).isEqualTo("8000");
            assertThat(content.get(2).get("runningBalance").asText()).isEqualTo("5000");

            assertThat(balanceOf(wallet)).isEqualByComparingTo("6000");
        }
    }

    @Nested
    @DisplayName("Idempotence")
    class Idempotency {

        @Test
        @DisplayName("rejouer la meme requete renvoie l'ecriture existante, sans second mouvement")
        void replayReturnsTheExistingEntry() {
            String wallet = openWallet("replay");
            String key = "replay-" + suffix;

            ApiResponse first = postEntry(key, "encaissement",
                    line("1100", "DR", "1000"), line(wallet, "CR", "1000"));
            assertThat(first.status()).isEqualTo(201);

            ApiResponse second = postEntry(key, "encaissement",
                    line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

            // 200 et non 201 : l'appel n'a produit aucun effet, il en a retrouve un.
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.entryRef()).isEqualTo(first.entryRef());
            assertThat(balanceOf(wallet)).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("une ecriture decimale differente du meme montant reste un rejeu")
        void scaleDifferenceIsStillAReplay() {
            String wallet = openWallet("scale");
            String key = "scale-" + suffix;

            ApiResponse first = postEntry(key, "encaissement",
                    line("1100", "DR", "1000"), line(wallet, "CR", "1000"));
            ApiResponse second = postEntry(key, "encaissement",
                    line("1100", "DR", "1000.00"), line(wallet, "CR", "1000.0000"));

            assertThat(second.status()).isEqualTo(200);
            assertThat(second.entryRef()).isEqualTo(first.entryRef());
        }

        @Test
        @DisplayName("meme cle et contenu different est refuse : c'est un bug appelant, pas un rejeu")
        void reusedKeyWithDifferentContentIsRejected() {
            String wallet = openWallet("reuse");
            String key = "reuse-" + suffix;

            postEntry(key, "encaissement", line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

            ApiResponse conflicting = postEntry(key, "encaissement",
                    line("1100", "DR", "2000"), line(wallet, "CR", "2000"));

            // Repondre 200 en renvoyant l'ancienne ecriture ferait croire a l'appelant que
            // ses 2 000 ont ete comptabilises. C'est la maniere la plus discrete de perdre
            // un paiement.
            assertThat(conflicting.status()).isEqualTo(422);
            assertThat(conflicting.code()).isEqualTo("LEDGER_IDEMPOTENCY_KEY_REUSED");
            assertThat(balanceOf(wallet)).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("l'en-tete Idempotency-Key est obligatoire")
        void keyIsMandatory() {
            String wallet = openWallet("nokey");
            ApiResponse response = post("/v1/journal-entries", null, """
                    {"description":"sans cle","lines":[%s,%s]}
                    """.formatted(line("1100", "DR", "100"), line(wallet, "CR", "100")));

            assertThat(response.status()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("Refus")
    class Rejections {

        @Test
        @DisplayName("une ecriture desequilibree est refusee avec un code exploitable")
        void unbalanced() {
            String wallet = openWallet("unbalanced");
            ApiResponse response = postEntry("unbalanced-" + suffix, "desequilibree",
                    line("1100", "DR", "10000"), line(wallet, "CR", "9999"));

            assertThat(response.status()).isEqualTo(422);
            assertThat(response.code()).isEqualTo("LEDGER_UNBALANCED_ENTRY");
            assertThat(response.body().get("detail").asText()).contains("ecart");
        }

        @Test
        @DisplayName("une ecriture a une seule ligne est refusee")
        void singleLine() {
            ApiResponse response = post("/v1/journal-entries", "single-" + suffix, """
                    {"description":"une seule ligne","lines":[%s]}
                    """.formatted(line("1100", "DR", "100")));

            // Refuse par la validation du contrat : minItems vaut 2.
            assertThat(response.status()).isEqualTo(400);
        }

        @Test
        @DisplayName("un montant XAF a decimales est refuse, jamais arrondi")
        void fractionalXaf() {
            String wallet = openWallet("fractional");
            ApiResponse response = postEntry("fractional-" + suffix, "montant a decimales",
                    line("1100", "DR", "1500.50"), line(wallet, "CR", "1500.50"));

            assertThat(response.status()).isEqualTo(422);
            assertThat(response.code()).isEqualTo("MONEY_INVALID_SCALE");
        }

        @Test
        @DisplayName("ecrire sur un compte de regroupement est refuse")
        void nonPostableAccount() {
            // 2100 agrege les portefeuilles clients. Une ecriture doit designer un
            // portefeuille precis, sans quoi l'argent appartiendrait a tout le monde.
            ApiResponse response = postEntry("grouping-" + suffix, "ecriture sur regroupement",
                    line("1100", "DR", "100"), line("2100", "CR", "100"));

            assertThat(response.status()).isEqualTo(422);
            assertThat(response.code()).isEqualTo("LEDGER_ACCOUNT_NOT_POSTABLE");
        }

        @Test
        @DisplayName("un compte inexistant produit un 404, pas un 500")
        void unknownAccount() {
            ApiResponse response = postEntry("unknown-" + suffix, "compte inconnu",
                    line("1100", "DR", "100"), line("2100.jamais-ouvert", "CR", "100"));

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.code()).isEqualTo("LEDGER_ACCOUNT_NOT_FOUND");
        }

        @Test
        @DisplayName("une devise incompatible avec le compte est refusee")
        void currencyMismatch() {
            String wallet = openWallet("currency");
            ApiResponse response = post("/v1/journal-entries", "currency-" + suffix, """
                    {
                      "description": "devise incompatible",
                      "lines": [
                        {"accountNumber":"1100","direction":"DR","amount":"100","currency":"EUR"},
                        {"accountNumber":"%s","direction":"CR","amount":"100","currency":"EUR"}
                      ]
                    }
                    """.formatted(wallet));

            assertThat(response.status()).isEqualTo(422);
            assertThat(response.code()).isEqualTo("LEDGER_ACCOUNT_CURRENCY_MISMATCH");
        }

        @Test
        @DisplayName("la reponse d'erreur porte le correlationId, pas la trace serveur")
        void errorsAreRfc7807() {
            String wallet = openWallet("problem");
            ApiResponse response = postEntry("problem-" + suffix, "desequilibree",
                    line("1100", "DR", "10"), line(wallet, "CR", "9"));

            assertThat(response.body().get("type").asText()).startsWith("https://ocb.dev/problems/");
            assertThat(response.body().has("correlationId")).isTrue();
            assertThat(response.body().toString()).doesNotContain("java.lang");
            assertThat(response.body().toString()).doesNotContain("org.springframework");
        }
    }

    @Nested
    @DisplayName("Consultation")
    class Reads {

        @Test
        @DisplayName("une ecriture se relit avec ses lignes numerotees")
        void entryIsReadableById() {
            String wallet = openWallet("read");
            ApiResponse posted = postEntry("read-" + suffix, "encaissement",
                    line("1100", "DR", "9850"),
                    line("5100", "DR", "150"),
                    line(wallet, "CR", "9900"),
                    line("4100", "CR", "100"));

            ApiResponse fetched = get("/v1/journal-entries/" + posted.entryRef());

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(fetched.body().get("lines")).hasSize(4);
            assertThat(fetched.body().get("lines").get(0).get("lineNo").asInt()).isEqualTo(1);
            assertThat(fetched.body().get("entrySeq").asLong()).isPositive();
        }

        @Test
        @DisplayName("une ecriture inexistante produit un 404")
        void unknownEntry() {
            assertThat(get("/v1/journal-entries/JE-NEXISTE-PAS").status()).isEqualTo(404);
        }

        @Test
        @DisplayName("le solde indique a quelle ecriture il correspond")
        void balanceCarriesItsEntrySeq() {
            String wallet = openWallet("seq");
            postEntry("seq-" + suffix, "credit", line("1100", "DR", "100"), line(wallet, "CR", "100"));

            ApiResponse balance = get("/v1/accounts/%s/balance".formatted(wallet));
            assertThat(balance.body().get("entrySeq").asLong()).isPositive();
            assertThat(balance.body().get("currency").asText()).isEqualTo("XAF");
            assertThat(balance.body().get("balance").asText()).isEqualTo("100");
        }
    }
}
