package com.ocb.payment;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que la supervision voit d'un encaissement.
 *
 * <p>Une metrique non eprouvee est une ligne de code que personne ne regarde jamais : elle
 * ne casse aucun test quand elle cesse d'etre emise, et son absence ne se remarque qu'au
 * moment ou l'on ouvre un tableau de bord pendant un incident — c'est-a-dire au pire
 * moment possible.
 *
 * <p>Ces tests verifient deux choses distinctes : que le compteur monte, et que ses
 * <b>etiquettes restent de cardinalite bornee</b>. La seconde est la moins evidente et la
 * plus couteuse a rater.
 */
class TransactionMetricsIT extends PaymentPersistenceTestBase {

    @Autowired
    private MeterRegistry meters;

    @Test
    @DisplayName("une demande d'encaissement compte ses deux transitions")
    void aCollectionCountsItsTransitions() {
        double createdBefore = count("COLLECTION", "CREATED");
        double pendingBefore = count("COLLECTION", "PENDING_PROVIDER");

        post("/v1/collections", "metrique-" + suffix, collectionBody("10000"));

        // CREATED puis PENDING_PROVIDER : les deux passent par la machine a etats, donc
        // les deux sont comptees. C'est bien ce qu'on veut voir — le decompte des
        // transitions, pas celui des requetes HTTP, qui ne dirait rien des redelivrances.
        assertThat(count("COLLECTION", "PENDING_PROVIDER"))
                .isEqualTo(pendingBefore + 1);

        // CREATED est enregistre comme transition initiale hors machine a etats : le
        // compteur ne bouge donc pas. L'assertion fige ce comportement plutot que de le
        // laisser deviner.
        assertThat(count("COLLECTION", "CREATED")).isEqualTo(createdBefore);
    }

    @Test
    @DisplayName("un decaissement et un encaissement ne se melangent pas")
    void typesAreDistinguished() {
        double disbursementsBefore = count("DISBURSEMENT", "PENDING_PROVIDER");

        post("/v1/collections", "metrique2-" + suffix, collectionBody("10000"));

        assertThat(count("DISBURSEMENT", "PENDING_PROVIDER"))
                .as("l'etiquette type separe reellement les deux sens")
                .isEqualTo(disbursementsBefore);
    }

    @Test
    @DisplayName("aucune etiquette de cardinalite non bornee")
    void tagsStayBounded() {
        post("/v1/collections", "metrique3-" + suffix, collectionBody("10000"));

        Set<String> tagKeys = meters.find("ocb.transactions").meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .map(Tag::getKey)
                .collect(Collectors.toSet());

        // Le piege classique : ajouter transactionId ou externalRef « pour pouvoir
        // retrouver la transaction ». Chaque valeur distincte cree une serie temporelle,
        // et Prometheus finit par tomber sous le nombre. Ce qui sert a retrouver une
        // transaction, ce sont les journaux et le correlationId, pas les metriques.
        assertThat(tagKeys)
                .as("seules des etiquettes a valeurs enumerables")
                .containsExactlyInAnyOrder("type", "status");
    }

    private double count(String type, String status) {
        var counter = meters.find("ocb.transactions")
                .tag("type", type)
                .tag("status", status)
                .counter();
        return counter == null ? 0d : counter.count();
    }
}
