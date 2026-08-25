package com.ocb.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le transfert entre portefeuilles : la demonstration inverse de la saga.
 *
 * <p>Ce que ces tests etablissent n'est pas seulement qu'un transfert fonctionne, mais
 * qu'il <b>n'a besoin d'aucune saga</b> : une seule ecriture, aucun etat intermediaire,
 * aucune compensation possible parce qu'il n'y a rien a compenser. C'est la contrepartie
 * utile du decaissement — elle montre que la saga y est presente par necessite et non par
 * gout du motif.
 *
 * <p>Ce qui reste commun aux deux : l'interdiction du decouvert, verifiee ici aussi.
 */
class TransferIT extends StubbedLedgerTestBase {

    private static final String AMOUNT = "2000";
    private static final String FEE = "20";
    private static final String FUNDS = "10000";

    private String destination;

    @BeforeEach
    void fundTheSender() {
        stub.credit(wallet, FUNDS);
        destination = "2100.wallet-dest-" + suffix;
        stub.credit(destination, "0");
    }

    @Test
    @DisplayName("une seule ecriture deplace l'argent, et elle est terminee a la reponse")
    void oneEntryAndItIsDone() {
        ApiResponse response = post("/v1/transfers", "tr-" + suffix, transferBody(AMOUNT));

        assertThat(response.status())
                .as("201 et non 202 : rien ne reste a attendre")
                .isEqualTo(201);
        assertThat(response.status_())
                .as("aucun etat intermediaire ne subsiste")
                .isEqualTo("COMPLETED");

        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("l'emetteur supporte le montant et les frais")
                .isEqualByComparingTo(new BigDecimal(FUNDS)
                        .subtract(new BigDecimal(AMOUNT)).subtract(new BigDecimal(FEE)));
        assertThat(stub.balanceOfWallet(destination).amount())
                .as("le destinataire recoit le montant demande, pas le montant moins les frais")
                .isEqualByComparingTo(new BigDecimal(AMOUNT));

        // Le coeur de la demonstration : une ecriture, pas deux, et aucun compte de
        // passage. Un decaissement en produirait deux et ferait stationner des fonds
        // en 1900 entre les deux.
        assertThat(entriesTouching(wallet))
                .as("un transfert ne se decompose pas en etapes")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("aucun evenement d'etape n'est publie, seulement l'issue")
    void onlyTheOutcomeIsPublished() {
        ApiResponse response = post("/v1/transfers", "tr2-" + suffix, transferBody(AMOUNT));
        String transactionId = response.transactionId();

        assertThat(outboxCount(transactionId, "payment.transfer.completed")).isEqualTo(1);

        // Il n'existe pas de payment.transfer.requested, et ce n'est pas un oubli : il n'y
        // a pas d'intervalle entre la demande et son issue pendant lequel un observateur
        // aurait quelque chose a apprendre.
        assertThat(outboxCount(transactionId, "provider.disbursement.execute")).isZero();
        assertThat(outboxCount(transactionId, "provider.collection.execute")).isZero();
    }

    @Test
    @DisplayName("le decouvert est interdit ici aussi")
    void insufficientFundsIsRefused() {
        ApiResponse response = post("/v1/transfers", "tr3-" + suffix, transferBody("999999"));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("PAYMENT_INSUFFICIENT_FUNDS");
        assertThat(stub.balanceOfWallet(wallet).amount()).isEqualByComparingTo(new BigDecimal(FUNDS));
        assertThat(stub.balanceOfWallet(destination).amount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("un transfert vers soi-meme est refuse plutot que facture")
    void selfTransferIsRefused() {
        String body = """
                {
                  "externalRef": "TX-%s",
                  "amount": "%s",
                  "currency": "XAF",
                  "fromWalletAccountRef": "%s",
                  "toWalletAccountRef": "%s"
                }
                """.formatted(suffix, AMOUNT, wallet, wallet);

        ApiResponse response = post("/v1/transfers", "tr4-" + suffix, body);

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("PAYMENT_SAME_WALLET_TRANSFER");

        // Le point du test : sans ce controle, l'operation serait comptablement nulle
        // mais preleverait quand meme la commission.
        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("aucune commission sur une operation qui ne deplace rien")
                .isEqualByComparingTo(new BigDecimal(FUNDS));
    }

    @Test
    @DisplayName("rejouer la cle ne transfere pas deux fois")
    void replayTransfersOnce() {
        String key = "tr5-" + suffix;
        String body = transferBody(AMOUNT);

        ApiResponse first = post("/v1/transfers", key, body);
        ApiResponse replay = post("/v1/transfers", key, body);

        assertThat(first.status()).isEqualTo(201);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());

        assertThat(stub.balanceOfWallet(destination).amount())
                .as("le destinataire n'a ete credite qu'une fois")
                .isEqualByComparingTo(new BigDecimal(AMOUNT));
    }

    @Test
    @DisplayName("la transaction ne declare aucun operateur")
    void noProviderIsClaimed() {
        ApiResponse response = post("/v1/transfers", "tr6-" + suffix, transferBody(AMOUNT));

        // Le champ a quitte les proprietes requises du contrat pour cette raison :
        // inventer un operateur ici inscrirait une contre-verite en base, et la contrainte
        // conditionnelle de la migration V5 la refuserait de toute facon.
        assertThat(response.body().hasNonNull("providerCode"))
                .as("un transfert ne passe par aucun operateur")
                .isFalse();
        assertThat(response.body().get("type").asText()).isEqualTo("TRANSFER");
    }

    // --- Utilitaires -------------------------------------------------------------------

    private String transferBody(String amount) {
        return """
                {
                  "externalRef": "TX-%s",
                  "amount": "%s",
                  "currency": "XAF",
                  "fromWalletAccountRef": "%s",
                  "toWalletAccountRef": "%s"
                }
                """.formatted(suffix, amount, wallet, destination);
    }

    private long entriesTouching(String walletAccountRef) {
        return stub.postedEntries().stream()
                .filter(entry -> entry.lines().stream()
                        .anyMatch(line -> walletAccountRef.equals(line.accountNumber())))
                .count();
    }
}
