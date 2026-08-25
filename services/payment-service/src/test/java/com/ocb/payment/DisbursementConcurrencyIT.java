package com.ocb.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'interdiction du decouvert, sous concurrence reelle.
 *
 * <p><b>Sans ce test, la regle ne serait qu'une intention.</b> Un controle de solde ecrit
 * naivement passe tous les tests sequentiels du monde : il ne se trompe que lorsque deux
 * demandes le franchissent en meme temps. Le seul moyen de savoir si le verrou fait son
 * travail est de le mettre reellement sous pression.
 *
 * <p>Le montage est construit pour ne laisser aucune echappatoire : trente-deux demandes
 * partent au meme instant sur <b>le meme portefeuille</b>, chacune avec une cle
 * d'idempotence <b>differente</b> — donc aucune n'est le rejeu d'une autre, et
 * l'idempotence ne peut pas les departager a la place du verrou — et le portefeuille ne
 * contient de quoi en financer qu'<b>une seule</b>.
 *
 * <p>Sans verrou, plusieurs fils liraient le meme solde initial avant que le premier
 * n'ecrive, et se croiraient tous finançables. Le portefeuille finirait a decouvert sans
 * qu'aucune demande, prise isolement, n'ait enfreint la regle.
 */
class DisbursementConcurrencyIT extends StubbedLedgerTestBase {

    private static final int CONCURRENT_CALLS = 32;

    /** De quoi financer exactement un decaissement de 5 000 augmente de 1 % de frais. */
    private static final String WALLET_FUNDS = "5050";

    @BeforeEach
    void fundOneDisbursementOnly() {
        // Le socle a deja remis la doublure a neuf et nomme le portefeuille.
        stub.credit(wallet, WALLET_FUNDS);
    }

    @Test
    @DisplayName("trente-deux decaissements simultanes, un seul portefeuille : un seul passe")
    void onlyOneConcurrentDisbursementIsFunded() throws Exception {
        List<ApiResponse> responses = fireConcurrently();

        long accepted = responses.stream().filter(r -> r.status() == 202).count();
        long refused = responses.stream().filter(r -> r.status() == 422).count();

        assertThat(accepted)
                .as("le portefeuille ne couvre qu'une seule demande")
                .isEqualTo(1);
        assertThat(refused)
                .as("toutes les autres sont refusees pour solde insuffisant")
                .isEqualTo(CONCURRENT_CALLS - 1);

        assertThat(responses.stream()
                .filter(r -> r.status() == 422)
                .map(ApiResponse::code)
                .distinct())
                .as("refusees pour la bonne raison, et non par un conflit fortuit")
                .containsExactly("PAYMENT_INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("le portefeuille ne passe jamais a decouvert")
    void theWalletNeverGoesNegative() throws Exception {
        fireConcurrently();

        BigDecimal finalBalance = stub.balanceOfWallet(wallet).amount();

        // L'assertion la plus directe, et celle qui echouerait bruyamment si le verrou
        // etait retire : c'est le decouvert lui-meme qu'on interdit, pas seulement le
        // nombre de reponses 202.
        assertThat(finalBalance)
                .as("un portefeuille client a decouvert, c'est de l'argent que nous avons "
                        + "envoye sans l'avoir")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(finalBalance)
                .as("exactement une demande a consomme les fonds")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("une seule ecriture d'engagement est passee au grand livre")
    void onlyOneReservationReachesTheLedger() throws Exception {
        fireConcurrently();

        // Filtre sur le portefeuille de CE test : la doublure est un singleton partage par
        // la classe et ses ecritures s'accumulent d'une methode a l'autre. Compter
        // globalement rendrait l'assertion dependante de l'ordre d'execution.
        long reservations = stub.postedEntries().stream()
                .filter(entry -> entry.entryRef() != null
                        && entry.entryRef().startsWith("DISB-RES-"))
                .filter(entry -> entry.lines().stream()
                        .anyMatch(line -> wallet.equals(line.accountNumber())))
                .count();

        // Le controle en aval du precedent : meme si les codes de reponse etaient corrects
        // par accident, une seconde ecriture aurait bel et bien deplace de l'argent.
        assertThat(reservations).isEqualTo(1);
    }

    /**
     * Trente-deux appels relaches au meme instant.
     *
     * <p>La barriere de depart compte autant que le nombre d'appels : sans elle, le pool
     * demarrerait les fils les uns apres les autres et le premier aurait souvent fini
     * avant que le dernier ne commence. Le test passerait alors sans jamais avoir mis le
     * verrou a l'epreuve.
     */
    private List<ApiResponse> fireConcurrently() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        List<ApiResponse> responses = new ArrayList<>();
        try {
            CountDownLatch startLine = new CountDownLatch(1);
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CALLS; i++) {
                // Une cle distincte par appel : ce ne sont pas des rejeux. Avec une cle
                // commune, l'idempotence suffirait a n'en laisser passer qu'un et le test
                // ne dirait rien du verrou.
                String key = "disb-concurrent-%s-%d".formatted(suffix, i);
                futures.add(pool.submit(() -> {
                    startLine.await();
                    return post("/v1/disbursements", key, disbursementBody("5000", wallet));
                }));
            }
            startLine.countDown();
            for (Future<ApiResponse> future : futures) {
                responses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }
        return responses;
    }
}
