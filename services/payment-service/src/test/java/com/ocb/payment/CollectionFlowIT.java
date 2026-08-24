package com.ocb.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.ocb.platform.events.EventEnvelope;
import com.ocb.platform.events.EventJson;
import com.ocb.platform.events.EventTypes;
import com.ocb.platform.events.Payloads;
import com.ocb.platform.events.Topics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * L'encaissement de bout en bout, en traversant reellement Kafka.
 *
 * <p>La chaine testee est longue et entierement asynchrone :
 *
 * <pre>
 * POST /v1/collections -> ligne outbox -> relais -> Kafka
 *   -> operateur simule -> evenements operateur -> Kafka
 *   -> consommateur payment -> grand livre -> transitions -> COMPLETED
 * </pre>
 *
 * <p><b>Regle imposee a tout ce fichier : aucune assertion ne porte sur le temps.</b> Un
 * test qui attend "assez longtemps" passe sur un poste de developpement et casse sur un
 * runner charge. Les attentes portent uniquement sur un etat observable, via Awaitility,
 * et jamais sur une duree codee en dur.
 */
class CollectionFlowIT extends PaymentKafkaTestBase {

    // -----------------------------------------------------------------------------
    // S1 — boucle nominale
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("S1 - encaissement complet : etats, ecriture comptable et evenements")
    void nominalCollection() {
        stubLedgerAccepts();

        String transactionId = requestCollection("10000");
        awaitStatus(transactionId, "COMPLETED");

        // La sequence est asserte EXACTEMENT, et non par simple presence des etapes.
        //
        // Ce que cela verifie reellement, c'est la garantie d'ordre par cle de partition.
        // Les evenements "accepted" et "succeeded" portent le meme transactionId, donc la
        // meme cle, donc la meme partition : Kafka les livre dans l'ordre d'ecriture. Si
        // "succeeded" arrivait le premier, la machine a etats refuserait la transition
        // PENDING_PROVIDER -> PROVIDER_CONFIRMED et cette sequence ne correspondrait plus.
        // Autrement dit, on verifie la garantie plutot que de l'esperer.
        assertThat(acceptedTransitions(transactionId)).containsExactly(
                "-->CREATED",
                "CREATED->PENDING_PROVIDER",
                "PENDING_PROVIDER->PROVIDER_ACCEPTED",
                "PROVIDER_ACCEPTED->PROVIDER_CONFIRMED",
                "PROVIDER_CONFIRMED->POSTING",
                "POSTING->COMPLETED");

        assertThat(rejectedTransitions(transactionId))
                .as("aucun refus attendu sur le chemin nominal")
                .isEmpty();

        // Un seul appel au grand livre : l'argent n'a bouge qu'une fois.
        assertThat(ledgerCallsFor(transactionId)).isEqualTo(1);

        // L'ecriture du plan de comptes. Le client envoie 10 000 ; la plateforme prend 1 %,
        // l'operateur preleve 1,5 % avant de nous crediter. Ignorer la commission de
        // l'operateur produirait une ecriture desequilibree.
        JsonNode body = ledgerRequestBodiesFor(transactionId).getFirst();
        assertThat(linesOf(body)).containsExactlyInAnyOrder(
                "1100|DR|9850",
                "5100|DR|150",
                "2100.wallet-" + suffix + "|CR|9900",
                "4100|CR|100");
        assertBalanced(body, "10000");

        // Les trois evenements existent des que le statut est COMPLETED : chacun a ete
        // ecrit dans la meme transaction que le changement d'etat qui l'a produit.
        assertThat(outboxEventTypes(transactionId)).containsExactly(
                "provider.collection.execute",
                "payment.collection.requested",
                "payment.collection.completed");

        // Leur PUBLICATION, elle, arrive apres : le relais est asynchrone, c'est tout
        // l'interet de l'outbox. Il faut donc l'attendre au lieu de l'affirmer.
        awaitOutboxDrained(transactionId);

        assertThat(ledgerEntryRefOf(transactionId)).isNotBlank();
    }

    // -----------------------------------------------------------------------------
    // S2 — refus definitif de l'operateur
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("S2 - refus operateur : echec, et surtout aucune ecriture comptable")
    void providerDeclines() {
        stubLedgerAccepts();

        String transactionId = requestCollection("10098");
        awaitStatus(transactionId, "FAILED");

        assertThat(acceptedTransitions(transactionId)).containsExactly(
                "-->CREATED",
                "CREATED->PENDING_PROVIDER",
                "PENDING_PROVIDER->PROVIDER_ACCEPTED",
                "PROVIDER_ACCEPTED->PROVIDER_DECLINED",
                "PROVIDER_DECLINED->FAILED");

        // L'assertion qui compte. Un encaissement refuse n'a rien engage : il n'y a rien
        // a ecrire, et donc rien a compenser. C'est ce qui distingue l'encaissement du
        // decaissement, ou le portefeuille est debite avant l'appel a l'operateur.
        assertThat(ledgerCallsFor(transactionId))
                .as("un encaissement refuse ne doit produire aucune ecriture")
                .isZero();

        assertThat(outboxEventTypes(transactionId))
                .contains("payment.collection.failed")
                .doesNotContain("payment.collection.completed");

        assertThat(failureCodeOf(transactionId)).isEqualTo("INSUFFICIENT_FUNDS");
    }

    // -----------------------------------------------------------------------------
    // S3 — doublon LOGIQUE
    // -----------------------------------------------------------------------------

    /**
     * Deux scenarios de duplication existent dans ce fichier, et ils ne sont pas
     * redondants : ils couvrent des problemes differents, arretes par des mecanismes
     * differents. Un test qui n'en couvrirait qu'un laisserait croire qu'un seul
     * mecanisme suffit.
     *
     * <p><b>S3, ici, teste le doublon LOGIQUE</b> : deux messages <i>distincts</i>, avec
     * des {@code eventId} <b>differents</b>, qui decrivent le meme fait. C'est ce qui
     * arrive quand un callback operateur et le resultat d'un polling remontent la meme
     * confirmation. La table {@code processed_message} ne voit rien passer — les deux
     * identifiants sont nouveaux — et seule la machine a etats peut neutraliser le second.
     *
     * <p><b>S5 teste le doublon TECHNIQUE</b> : le <i>meme</i> message reemis, avec le
     * meme {@code eventId}, parce que Kafka livre au moins une fois. La machine a etats
     * ne suffirait pas a le rendre inoffensif dans le cas general, et c'est la
     * deduplication qui l'arrete.
     */
    @Test
    @DisplayName("S3 - doublon logique : deux succes distincts, une seule ecriture")
    void duplicateProviderCallback() {
        stubLedgerAccepts();

        // Montant termine par 96 : l'operateur simule publie le succes DEUX FOIS, avec
        // des eventId differents.
        String transactionId = requestCollection("10096");
        awaitStatus(transactionId, "COMPLETED");

        // On attend d'abord la trace du refus, et ce n'est pas un detail d'ordonnancement.
        //
        // Le second succes porte un eventId different : il franchit la deduplication et
        // n'est arrete que par la machine a etats, qui journalise son refus. Asserter
        // immediatement "un seul mouvement" passerait a vide si ce second message n'etait
        // pas encore arrive — le test serait vert sans rien avoir verifie.
        //
        // Exactement un refus, pas "au moins un" : le premier succes est accepte, le
        // second refuse. Un refus supplementaire signalerait un comportement inattendu.
        awaitRejectedTransitions(transactionId, 1);

        assertThat(rejectedTransitions(transactionId).getFirst())
                .containsAnyOf("TERMINAL_STATE", "ALREADY_IN_TARGET_STATE", "ILLEGAL_TRANSITION");

        // L'assertion centrale, desormais adossee a la preuve que le doublon a ete vu.
        assertThat(ledgerCallsFor(transactionId))
                .as("un doublon logique ne doit pas produire une seconde ecriture")
                .isEqualTo(1);

        assertThat(outboxEventTypes(transactionId))
                .filteredOn("payment.collection.completed"::equals)
                .as("un seul evenement de fin")
                .hasSize(1);
    }

    // -----------------------------------------------------------------------------
    // S4 — silence de l'operateur
    // -----------------------------------------------------------------------------

    @Test
    @DisplayName("S4 - silence de l'operateur : la transaction attend, elle n'echoue jamais")
    void providerNeverConcludes() {
        stubLedgerAccepts();

        // Montant termine par 97 : l'operateur simule accuse reception puis ne conclut
        // jamais. C'est le cas du timeout, ou l'argent a peut-etre bouge.
        String silent = requestCollection("10097");
        awaitStatus(silent, "PROVIDER_ACCEPTED");

        // ---------------------------------------------------------------------------
        // La transaction-barriere.
        //
        // Prouver qu'il ne se passe RIEN est le cas le plus difficile d'un test
        // asynchrone : aucune attente ne le demontre, puisqu'on aura toujours pu
        // attendre trop peu. Le raisonnement repose ici sur deux jambes, dont aucune
        // ne suffirait seule.
        //
        // 1. Avoir observe PROVIDER_ACCEPTED prouve que l'operateur simule a TERMINE de
        //    traiter cette commande : il publie l'accuse de reception en premier, puis
        //    sa branche "ne jamais conclure" retourne immediatement. Aucun evenement
        //    supplementaire ne sera donc jamais produit pour cette transaction.
        //
        // 2. On poste ensuite une seconde transaction, nominale, et on attend qu'elle
        //    atteigne COMPLETED. Comme elle traverse le meme relais, le meme operateur
        //    simule et le meme consommateur, son aboutissement prouve que la chaine a
        //    ete DRAINEE au-dela du point ou un evenement tardif de la premiere aurait
        //    du apparaitre.
        //
        // Ensemble, les deux rendent l'assertion negative aussi rigoureuse qu'elle peut
        // l'etre sans arreter le temps. On n'affirme pas "rien n'est arrive parce qu'on
        // a attendu", mais "rien ne peut arriver, et le tuyau est vide".
        // ---------------------------------------------------------------------------
        String barrier = requestCollection("20000");
        awaitStatus(barrier, "COMPLETED");

        assertThat(statusOf(silent))
                .as("%s", diagnostics(silent))
                .isEqualTo("PROVIDER_ACCEPTED");

        assertThat(acceptedTransitions(silent))
                .as("aucune conclusion ne doit avoir ete tiree")
                .doesNotContain("PROVIDER_ACCEPTED->PROVIDER_DECLINED")
                .doesNotContain("PROVIDER_ACCEPTED->PROVIDER_CONFIRMED");

        assertThat(ledgerCallsFor(silent)).isZero();

        assertThat(outboxEventTypes(silent))
                .doesNotContain("payment.collection.completed")
                .doesNotContain("payment.collection.failed");
    }

    // -----------------------------------------------------------------------------
    // S5 — doublon TECHNIQUE
    // -----------------------------------------------------------------------------

    /**
     * Le pendant de S3 : ici le <b>meme</b> message est reemis, avec le <b>meme</b>
     * {@code eventId}. C'est ce que produit Kafka lorsqu'un consommateur redemarre entre
     * le traitement d'un message et la validation de son offset.
     *
     * <p>La machine a etats ne suffirait pas dans le cas general — un message rejoue avant
     * que le premier n'ait ete traite trouverait le meme etat de depart et serait accepte
     * deux fois. C'est {@code processed_message}, dont l'insertion a lieu dans la meme
     * transaction que l'effet metier et <b>avant</b> lui, qui l'arrete.
     *
     * <p>Le montant termine par 97 sert d'echafaudage : l'operateur simule accuse
     * reception puis se tait, ce qui laisse le test maitre du moment ou la confirmation
     * arrive et de son contenu.
     */
    @Test
    @DisplayName("S5 - doublon technique : meme eventId rejoue, un seul effet")
    void duplicateTechnicalMessage() throws Exception {
        stubLedgerAccepts();

        String transactionId = requestCollection("10097");
        awaitStatus(transactionId, "PROVIDER_ACCEPTED");

        String duplicatedEventId = UUID.randomUUID().toString();
        String duplicated = succeededEnvelope(duplicatedEventId, transactionId, "REF-REPLAY");

        // Meme cle de partition : les copies arrivent dans l'ordre, sur la meme partition,
        // exactement comme le ferait une redelivrance.
        kafka.send(Topics.EVT_PROVIDER, transactionId, duplicated);
        kafka.send(Topics.EVT_PROVIDER, transactionId, duplicated);

        // Message marqueur, envoye derriere les deux copies avec un eventId DIFFERENT.
        //
        // Il resout le probleme propre a ce scenario : un doublon technique correctement
        // arrete ne laisse aucune trace, donc rien ne permet de savoir s'il a ete traite
        // ou s'il n'est pas encore arrive. Sans repere, l'assertion "un seul mouvement"
        // passerait a vide.
        //
        // Le marqueur, lui, franchit la deduplication et se fait refuser par la machine a
        // etats, ce qui laisse une trace. Comme il partage la cle de partition des deux
        // copies, il est necessairement traite APRES elles : voir son refus prouve
        // qu'elles ont ete consommees.
        kafka.send(Topics.EVT_PROVIDER, transactionId,
                succeededEnvelope(UUID.randomUUID().toString(), transactionId, "REF-MARQUEUR"));

        awaitStatus(transactionId, "COMPLETED");

        // Exactement un refus : celui du marqueur. Si la deduplication avait laisse passer
        // la seconde copie, elle aurait elle aussi ete refusee par la machine a etats et
        // on en compterait deux. Ce nombre est donc une assertion sur la deduplication
        // elle-meme, pas seulement un point de synchronisation.
        awaitRejectedTransitions(transactionId, 1);

        assertThat(ledgerCallsFor(transactionId))
                .as("le message rejoue ne doit produire aucun second mouvement")
                .isEqualTo(1);

        assertThat(processedMessageCount(duplicatedEventId))
                .as("une seule trace de traitement pour cet eventId")
                .isEqualTo(1);

        assertThat(outboxEventTypes(transactionId))
                .filteredOn("payment.collection.completed"::equals)
                .hasSize(1);
    }

    private String succeededEnvelope(String eventId, String transactionId, String providerRef)
            throws Exception {
        return EventJson.mapper().writeValueAsString(new EventEnvelope(
                eventId,
                EventTypes.PROVIDER_OPERATION_SUCCEEDED,
                1,
                Instant.now(),
                "ProviderOperation",
                transactionId,
                "corr-" + suffix,
                null,
                "test-harness",
                new Payloads.ProviderOperationSucceeded(
                        transactionId, "MTN_MOMO", providerRef, "150", "XAF",
                        Instant.now(), "POLL")));
    }

    // -----------------------------------------------------------------------------
    // S6 — grand livre injoignable
    // -----------------------------------------------------------------------------

    /**
     * La demonstration la plus directe de l'exigence « timeout n'est pas echec ».
     *
     * <p>Le grand livre repond 503 aux deux premieres tentatives, puis accepte. Trois
     * proprietes doivent tenir :
     *
     * <ul>
     *   <li>la transaction n'est <b>jamais</b> declaree en echec : une absence de reponse
     *       n'autorise aucune conclusion, l'ecriture a peut-etre eu lieu ;
     *   <li>le message est redelivre, ce qui suppose que la transaction locale — y compris
     *       l'insertion dans {@code processed_message} — a bien ete annulee ;
     *   <li>malgre plusieurs appels, une seule ecriture existe, grace a la cle
     *       d'idempotence derivee de l'identifiant de transaction.
     * </ul>
     */
    @Test
    @DisplayName("S6 - grand livre injoignable : retentative, jamais d'echec, une seule ecriture")
    void ledgerTemporarilyUnavailable() {
        String scenario = "ledger-recovery-" + suffix;

        LEDGER.stubFor(WireMock.post(urlEqualTo(LEDGER_PATH)).inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second-failure"));

        LEDGER.stubFor(WireMock.post(urlEqualTo(LEDGER_PATH)).inScenario(scenario)
                .whenScenarioStateIs("second-failure")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("recovered"));

        LEDGER.stubFor(WireMock.post(urlEqualTo(LEDGER_PATH)).inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"entryRef\":\"JE-RECOVERED-" + suffix + "\"}")));

        String transactionId = requestCollection("30000");
        awaitStatus(transactionId, "COMPLETED");

        assertThat(ledgerCallsFor(transactionId))
                .as("les deux echecs ont bien donne lieu a des retentatives")
                .isGreaterThanOrEqualTo(3);

        assertThat(acceptedTransitions(transactionId))
                .as("aucune conclusion hative : l'indeterminee ne devient pas un echec")
                .doesNotContain("POSTING->MANUAL_REVIEW")
                .doesNotContain("PROVIDER_DECLINED->FAILED");

        assertThat(statusOf(transactionId)).isEqualTo("COMPLETED");
        assertThat(ledgerEntryRefOf(transactionId)).isEqualTo("JE-RECOVERED-" + suffix);

        assertThat(outboxEventTypes(transactionId))
                .filteredOn("payment.collection.completed"::equals)
                .as("une seule fin, malgre plusieurs passages dans le consommateur")
                .hasSize(1);
    }

    // -----------------------------------------------------------------------------

    private List<String> linesOf(JsonNode ledgerRequest) {
        List<String> lines = new java.util.ArrayList<>();
        for (JsonNode line : ledgerRequest.get("lines")) {
            lines.add("%s|%s|%s".formatted(
                    line.get("accountNumber").asText(),
                    line.get("direction").asText(),
                    line.get("amount").asText()));
        }
        return lines;
    }

    /** Verifie que l'ecriture soumise au grand livre est equilibree, avant meme qu'il ne l'examine. */
    private void assertBalanced(JsonNode ledgerRequest, String expectedTotal) {
        BigDecimal debits = BigDecimal.ZERO;
        BigDecimal credits = BigDecimal.ZERO;
        for (JsonNode line : ledgerRequest.get("lines")) {
            BigDecimal amount = new BigDecimal(line.get("amount").asText());
            if ("DR".equals(line.get("direction").asText())) {
                debits = debits.add(amount);
            } else {
                credits = credits.add(amount);
            }
        }
        assertThat(debits).isEqualByComparingTo(expectedTotal);
        assertThat(credits).isEqualByComparingTo(expectedTotal);
    }

    private String failureCodeOf(String transactionId) {
        return jdbc.sql("SELECT failure_code FROM payment.payment_transaction WHERE id = :id")
                .param("id", UUID.fromString(transactionId))
                .query(String.class)
                .single();
    }
}
