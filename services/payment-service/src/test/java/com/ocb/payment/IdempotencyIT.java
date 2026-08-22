package com.ocb.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'idempotence de la couche HTTP, y compris sous concurrence reelle.
 *
 * <p>Un test sequentiel ne prouverait rien : il suffirait de relire avant d'ecrire pour le
 * faire passer, et cette approche se casse des que deux requetes arrivent ensemble. Le cas
 * qui compte est celui d'un client qui reessaie pendant que la premiere requete est encore
 * en vol — situation banale des que le reseau est mauvais, ce qui est la norme sur du
 * Mobile Money.
 */
class IdempotencyIT extends PaymentPersistenceTestBase {

    private static final int CONCURRENT_CALLS = 24;

    @Test
    @DisplayName("une demande acceptee rend 202 et laisse la transaction en attente de l'operateur")
    void firstRequestIsAccepted() {
        ApiResponse response = post("/v1/collections", "collect-" + suffix, collectionBody("10000"));

        assertThat(response.status()).as("%s", response.body()).isEqualTo(202);
        assertThat(response.status_()).isEqualTo("PENDING_PROVIDER");
        assertThat(response.body().get("platformFee").asText()).isEqualTo("100");

        // Le numero complet n'est jamais rendu ni conserve.
        assertThat(response.body().get("maskedMsisdn").asText()).isEqualTo("+2376****0001");
        assertThat(response.body().toString()).doesNotContain("670000001");
    }

    @Test
    @DisplayName("rejouer la meme requete rend 200 et la meme transaction")
    void replayReturnsTheSameTransaction() {
        String key = "replay-" + suffix;

        ApiResponse first = post("/v1/collections", key, collectionBody("10000"));
        ApiResponse second = post("/v1/collections", key, collectionBody("10000"));

        assertThat(first.status()).isEqualTo(202);
        assertThat(second.status()).as("l'appel n'a produit aucun effet, il en a retrouve un").isEqualTo(200);
        assertThat(second.transactionId()).isEqualTo(first.transactionId());

        // Une seule commande operateur a ete deposee : aucun second prelevement ne partira.
        assertThat(outboxCount(first.transactionId(), "provider.collection.execute")).isEqualTo(1);
    }

    @Test
    @DisplayName("une ecriture decimale differente du meme montant reste un rejeu")
    void scaleDifferenceIsStillAReplay() {
        String key = "scale-" + suffix;

        ApiResponse first = post("/v1/collections", key, collectionBody("10000"));
        ApiResponse second = post("/v1/collections", key, collectionBody("10000.00"));

        assertThat(second.status()).isEqualTo(200);
        assertThat(second.transactionId()).isEqualTo(first.transactionId());
    }

    @Test
    @DisplayName("meme cle et contenu different est refuse")
    void reusedKeyWithDifferentContentIsRejected() {
        String key = "reuse-" + suffix;
        ApiResponse first = post("/v1/collections", key, collectionBody("10000"));

        ApiResponse conflicting = post("/v1/collections", key, collectionBody("20000"));

        // Rendre l'ancienne transaction ferait croire a l'appelant que ses 20 000 ont ete
        // pris en charge. C'est la maniere la plus discrete de perdre un paiement.
        assertThat(conflicting.status()).isEqualTo(422);
        assertThat(conflicting.code()).isEqualTo("PAYMENT_IDEMPOTENCY_KEY_REUSED");
        assertThat(outboxCount(first.transactionId(), "provider.collection.execute")).isEqualTo(1);
    }

    @Test
    @DisplayName("24 requetes simultanees avec la meme cle ne creent qu'une transaction")
    void sameKeyConcurrentlyCreatesOneTransaction() throws Exception {
        String key = "concurrent-" + suffix;
        String body = collectionBody("10000");

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_CALLS);
        List<ApiResponse> responses = new ArrayList<>();
        try {
            CountDownLatch startLine = new CountDownLatch(1);
            List<Future<ApiResponse>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_CALLS; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await();
                    return post("/v1/collections", key, body);
                }));
            }
            startLine.countDown();
            for (Future<ApiResponse> future : futures) {
                responses.add(future.get());
            }
        } finally {
            pool.shutdownNow();
        }

        long accepted = responses.stream().filter(r -> r.status() == 202).count();
        long replayed = responses.stream().filter(r -> r.status() == 200).count();
        long inProgress = responses.stream().filter(r -> r.status() == 409).count();

        assertThat(accepted).as("une seule requete cree la transaction").isEqualTo(1);
        assertThat(replayed + inProgress)
                .as("toutes les autres sont soit un rejeu, soit invitees a reessayer")
                .isEqualTo(CONCURRENT_CALLS - 1);

        Set<String> distinctIds = responses.stream()
                .filter(r -> r.status() < 300)
                .map(ApiResponse::transactionId)
                .collect(Collectors.toSet());
        assertThat(distinctIds).hasSize(1);

        // Le controle qui compte : une seule commande partira vers l'operateur.
        assertThat(outboxCount(distinctIds.iterator().next(), "provider.collection.execute")).isEqualTo(1);
    }

    @Test
    @DisplayName("l'en-tete Idempotency-Key est obligatoire")
    void keyIsMandatory() {
        assertThat(post("/v1/collections", null, collectionBody("10000")).status()).isEqualTo(400);
    }

    @Test
    @DisplayName("un numero mal forme est refuse sans etre recopie dans la reponse")
    void malformedMsisdnIsRejected() {
        ApiResponse response = post("/v1/collections", "bad-msisdn-" + suffix,
                collectionBody("10000", "+00"));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().toString()).doesNotContain("+00\"");
    }
}
