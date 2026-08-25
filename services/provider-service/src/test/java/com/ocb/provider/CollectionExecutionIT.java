package com.ocb.provider;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La regle qui gouverne ce service, exercee contre un vrai serveur : <b>une absence de
 * reponse n'est jamais un echec</b>.
 *
 * <p>Chaque test oppose deux situations que le code doit distinguer :
 *
 * <ul>
 *   <li>l'operateur <b>dit non</b> — une reponse, dont on peut conclure ;
 *   <li>l'operateur <b>ne dit rien</b> — pas une reponse, dont on ne peut rien conclure.
 * </ul>
 *
 * <p>Les confondre reviendrait a declarer perdu un paiement peut-etre abouti, et a
 * rembourser un client qui a bien ete debite.
 */
class CollectionExecutionIT extends ProviderOperatorTestBase {

    @Test
    @DisplayName("l'operateur accepte : accuse de reception publie, relance programmee")
    void operatorAcceptsTheRequest() {
        operatorAccepts("MTN-" + suffix);

        UUID transactionId = requestCollection();

        assertThat(statusOf(transactionId)).isEqualTo("ACCEPTED");
        assertThat(outboxEventTypes(transactionId))
                .containsExactly("provider.operation.accepted");

        // Une relance reste programmee malgre l'accuse de reception : le rappel peut se
        // perdre, et c'est la relance qui garantit qu'on finira par savoir.
        assertThat(pollScheduled(transactionId)).isTrue();
    }

    @Test
    @DisplayName("l'operateur ne repond pas : aucune conclusion, aucun evenement")
    void operatorHangsAndNothingIsConcluded() {
        // Le serveur accepte la connexion et garde le fil ouvert au-dela du delai de
        // lecture. Ni erreur, ni refus : un silence.
        operatorHangs();

        UUID transactionId = requestCollection();

        // L'operation reste en attente. Elle ne devient surtout pas ACCEPTED — on n'a
        // recu aucun accuse — ni FAILED, puisque la demande est peut-etre parvenue.
        assertThat(statusOf(transactionId)).isEqualTo("PENDING");

        // Le point central : RIEN n'est publie. Un evenement d'echec ferait conclure au
        // moteur de paiement que rien n'a bouge, alors que le client a peut-etre deja ete
        // debite chez l'operateur.
        assertThat(outboxEventTypes(transactionId))
                .as("un silence ne produit aucun evenement")
                .isEmpty();

        assertThat(pollScheduled(transactionId))
                .as("une relance doit prendre le relais")
                .isTrue();
    }

    @Test
    @DisplayName("une erreur serveur est traitee comme un silence, pas comme un refus")
    void serverErrorIsNotARejection() {
        operatorReturnsServerError();

        UUID transactionId = requestCollection();

        // Un 503 signifie que l'operateur n'a pas su nous repondre, pas qu'il a refuse.
        // La distinction est invisible dans le code d'un client naif, et couteuse.
        assertThat(statusOf(transactionId)).isEqualTo("PENDING");
        assertThat(outboxEventTypes(transactionId)).isEmpty();
        assertThat(pollScheduled(transactionId)).isTrue();
    }

    @Test
    @DisplayName("un refus explicite est une reponse : l'echec est publie")
    void explicitRejectionIsPublished() {
        operatorRejects();

        UUID transactionId = requestCollection();

        // Contre-preuve indispensable. Sans elle, les tests precedents passeraient aussi
        // avec une implementation qui ne conclurait jamais rien.
        assertThat(statusOf(transactionId)).isEqualTo("FAILED");
        assertThat(outboxEventTypes(transactionId))
                .containsExactly("provider.operation.accepted", "provider.operation.failed");
        assertThat(pollScheduled(transactionId))
                .as("une operation tranchee n'est plus relancee")
                .isFalse();
    }

    @Test
    @DisplayName("la relance tranche ce que l'appel initial n'avait pas pu conclure")
    void pollingResolvesWhatTheTimeoutLeftOpen() {
        operatorHangs();
        UUID transactionId = requestCollection();
        assertThat(statusOf(transactionId)).isEqualTo("PENDING");

        // L'operateur va mieux et repond desormais. La relance interroge par NOTRE
        // reference : aucune reference operateur n'a jamais ete recue.
        statusIs("SUCCEEDED", ",\"fee\":\"150\",\"currency\":\"XAF\"");
        makePollDue(transactionId);
        poller.pollBatch();

        assertThat(statusOf(transactionId)).isEqualTo("SUCCEEDED");
        assertThat(outboxEventTypes(transactionId))
                .containsExactly("provider.operation.succeeded");
    }

    @Test
    @DisplayName("la relance peut aussi conclure a un refus")
    void pollingCanResolveToFailure() {
        operatorHangs();
        UUID transactionId = requestCollection();

        statusIs("FAILED", ",\"errorCode\":\"INSUFFICIENT_FUNDS\"");
        makePollDue(transactionId);
        poller.pollBatch();

        assertThat(statusOf(transactionId)).isEqualTo("FAILED");
        assertThat(outboxEventTypes(transactionId))
                .containsExactly("provider.operation.failed");
    }

    @Test
    @DisplayName("une relance sans reponse consomme du budget sans rien conclure")
    void pollingWithoutAnswerConcludesNothing() {
        operatorHangs();
        UUID transactionId = requestCollection();

        statusHangs();
        makePollDue(transactionId);
        poller.pollBatch();

        assertThat(statusOf(transactionId)).isEqualTo("PENDING");
        assertThat(outboxEventTypes(transactionId)).isEmpty();
        assertThat(pollAttemptsOf(transactionId)).isPositive();
        assertThat(pollScheduled(transactionId))
                .as("le budget n'est pas encore epuise")
                .isTrue();
    }

    @Test
    @DisplayName("budget epuise : l'operation devient UNRESOLVED, jamais FAILED")
    void exhaustedBudgetYieldsUnresolvedNotFailure() {
        operatorHangs();
        UUID transactionId = requestCollection();

        // On fait comme si l'operation attendait depuis plus longtemps que le budget.
        // Le budget etant mesure depuis la premiere emission, la prochaine relance ne
        // peut plus etre programmee.
        statusHangs();
        ageOperation(transactionId, 25);
        makePollDue(transactionId);
        poller.pollBatch();

        // La conclusion la plus importante du service : on declare son ignorance plutot
        // que d'inventer un echec. L'argent a peut-etre bouge, et seul un arbitrage
        // humain ou la reconciliation peut trancher.
        assertThat(statusOf(transactionId)).isEqualTo("UNRESOLVED");
        assertThat(outboxEventTypes(transactionId))
                .as("un type d'evenement distinct, qu'aucun consommateur ne peut confondre avec un echec")
                .containsExactly("provider.operation.unresolved");
        assertThat(pollScheduled(transactionId)).isFalse();
    }

    @Test
    @DisplayName("une commande rejouee ne rappelle jamais l'operateur")
    void replayedCommandNeverCallsTheOperatorTwice() {
        operatorAccepts("MTN-" + suffix);

        UUID transactionId = UUID.randomUUID();
        String externalRef = "TX-" + suffix;
        com.ocb.provider.domain.ProviderCode mtn = com.ocb.provider.domain.ProviderCode.MTN_MOMO;
        com.ocb.platform.domain.money.Money amount =
                com.ocb.platform.domain.money.Money.parse("10000", "XAF");

        operations.execute(transactionId, mtn, com.ocb.provider.domain.OperationType.COLLECTION,
                externalRef, "collection:" + transactionId, amount, "+237670000001", "corr-1");
        operations.execute(transactionId, mtn, com.ocb.provider.domain.OperationType.COLLECTION,
                externalRef, "collection:" + transactionId, amount, "+237670000001", "corr-1");

        // Un seul appel sortant. La contrainte d'unicite sur (operateur, transaction) est
        // le garde-fou ultime contre le double prelevement : elle tient meme si la
        // deduplication Kafka echouait.
        OPERATOR.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo("/collections")));

        assertThat(outboxEventTypes(transactionId))
                .as("un seul accuse de reception")
                .containsExactly("provider.operation.accepted");
    }

    @Test
    @DisplayName("la cle d'idempotence est transmise a l'operateur")
    void idempotencyKeyIsForwarded() {
        operatorAccepts("MTN-" + suffix);
        UUID transactionId = requestCollection();

        // Sans elle, une retentative apres delai depasse creerait un second paiement chez
        // l'operateur. C'est la contrepartie indispensable du fait qu'on retente.
        OPERATOR.verify(com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock
                        .urlEqualTo("/collections"))
                .withHeader("Idempotency-Key", com.github.tomakehurst.wiremock.client.WireMock
                        .equalTo("collection:" + transactionId)));
    }
}
