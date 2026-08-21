package com.ocb.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'idempotence sous concurrence reelle.
 *
 * <p>Un test sequentiel ne prouve pas grand-chose : il suffit de relire avant d'ecrire
 * pour le faire passer, et cette approche se casse des que deux requetes arrivent
 * ensemble. Le cas qui compte est celui d'un client qui reessaie pendant que la premiere
 * requete est encore en vol — situation banale des que le reseau est mauvais, ce qui est
 * la norme sur du Mobile Money.
 *
 * <p>Ce qui rend le comportement correct ici n'est pas du code applicatif mais la
 * combinaison d'une contrainte d'unicite et de {@code ON CONFLICT DO NOTHING} : une
 * insertion concurrente sur la meme cle attend l'issue de la premiere au lieu d'echouer.
 */
class ConcurrentIdempotencyIT extends LedgerIntegrationTestBase {

    private static final int CONCURRENT_CALLS = 32;

    @Test
    @DisplayName("32 requetes simultanees avec la meme cle ne produisent qu'un seul mouvement")
    void sameKeyConcurrentlyProducesOneEntry() throws Exception {
        String wallet = openWallet("concurrent");
        String key = "concurrent-" + suffix;
        String body = """
                {
                  "description": "encaissement rejoue en parallele",
                  "lines": [%s,%s]
                }
                """.formatted(line("1100", "DR", "10000"), line(wallet, "CR", "10000"));

        List<ApiResponse> responses = fireTogether(() -> post("/v1/journal-entries", key, body));

        long created = responses.stream().filter(r -> r.status() == 201).count();
        long replayed = responses.stream().filter(r -> r.status() == 200).count();

        assertThat(created).as("une seule requete cree l'ecriture").isEqualTo(1);
        assertThat(replayed).as("toutes les autres retrouvent la meme").isEqualTo(CONCURRENT_CALLS - 1);

        Set<String> distinctRefs = responses.stream()
                .map(ApiResponse::entryRef).collect(Collectors.toSet());
        assertThat(distinctRefs).as("toutes designent la meme ecriture").hasSize(1);

        // Le controle qui compte : l'argent n'a bouge qu'une fois.
        assertThat(balanceOf(wallet)).isEqualByComparingTo("10000");
    }

    @Test
    @DisplayName("des cles distinctes lancees ensemble produisent bien des mouvements distincts")
    void distinctKeysConcurrentlyAllApply() throws Exception {
        // Contre-preuve. Sans elle, une implementation qui refuserait tout sous
        // concurrence passerait le test precedent.
        String wallet = openWallet("distinct");
        String body = """
                {
                  "description": "encaissements independants",
                  "lines": [%s,%s]
                }
                """.formatted(line("1100", "DR", "100"), line(wallet, "CR", "100"));

        List<ApiResponse> responses = fireTogether(() ->
                post("/v1/journal-entries", "distinct-" + suffix + "-" + java.util.UUID.randomUUID(), body));

        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(balanceOf(wallet)).isEqualByComparingTo(String.valueOf(100 * CONCURRENT_CALLS));
    }

    /** Lance les appels au meme instant, pour maximiser le recouvrement plutot que l'esperer. */
    private List<ApiResponse> fireTogether(Callable<ApiResponse> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        try {
            CountDownLatch startLine = new CountDownLatch(1);
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CALLS; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    return call.call();
                }));
            }
            startLine.countDown();

            List<ApiResponse> responses = new ArrayList<>();
            for (Future<ApiResponse> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
