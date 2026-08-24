package com.ocb.provider;

import com.ocb.provider.domain.OperationStatus;
import com.ocb.provider.domain.ProviderOperation;
import com.ocb.provider.domain.WebhookSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La surface publique du systeme, de bout en bout.
 *
 * <p>Ces tests couvrent ce qui arrive reellement en production sur un point de
 * terminaison expose a Internet : des rappels legitimes, des doublons, des messages
 * tardifs, et des tentatives non authentifiees.
 */
class WebhookIT extends ProviderPersistenceTestBase {

    @Nested
    @DisplayName("Authentification")
    class Authentication {

        @Test
        @DisplayName("un rappel correctement signe est accepte et fait avancer l'operation")
        void genuineCallbackIsAccepted() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            ApiResponse response = postSignedCallback("MTN_MOMO",
                    callbackBody("evt-" + suffix, externalRef, "SUCCEEDED"));

            assertThat(response.status()).as("%s", response.body()).isEqualTo(200);
            assertThat(statusOf(operation.transactionId())).isEqualTo("SUCCEEDED");
            assertThat(outboxEventTypes(operation.transactionId()))
                    .containsExactly("provider.operation.succeeded");
        }

        @Test
        @DisplayName("une signature invalide est refusee et n'atteint aucune logique metier")
        void invalidSignatureIsRejected() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);
            String body = callbackBody("evt-" + suffix, externalRef, "SUCCEEDED");

            ApiResponse response = postCallback("MTN_MOMO", body,
                    "sha256=0000000000000000000000000000000000000000000000000000000000000000",
                    String.valueOf(Instant.now().getEpochSecond()));

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("PROVIDER_INVALID_SIGNATURE");

            // Le point essentiel : rien n'a bouge. Le filtre a arrete la requete avant
            // qu'elle n'atteigne le controleur, donc avant meme que Jackson n'analyse
            // une charge utile non authentifiee.
            assertThat(statusOf(operation.transactionId())).isEqualTo("ACCEPTED");
            assertThat(outboxEventTypes(operation.transactionId())).isEmpty();
            assertThat(callbackCount("evt-" + suffix)).isZero();
        }

        @Test
        @DisplayName("un corps altere apres signature est refuse")
        void tamperedBodyIsRejected() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            String signed = callbackBody("evt-" + suffix, externalRef, "SUCCEEDED");
            long timestamp = Instant.now().getEpochSecond();
            String signature = WebhookSignature.sign(MTN_SECRET, timestamp, signed);

            // Le montant de commission est gonfle apres coup, la signature reste celle
            // du corps d'origine.
            String tampered = signed.replace("\"fee\":\"150\"", "\"fee\":\"9999\"");

            ApiResponse response = postCallback("MTN_MOMO", tampered, signature,
                    String.valueOf(timestamp));

            assertThat(response.status()).isEqualTo(401);
            assertThat(statusOf(operation.transactionId())).isEqualTo("ACCEPTED");
        }

        @Test
        @DisplayName("une signature capturee et rejouee plus tard est refusee")
        void expiredSignatureIsRejected() {
            String externalRef = "TX-" + suffix;
            givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            String body = callbackBody("evt-" + suffix, externalRef, "SUCCEEDED");
            long old = Instant.now().minus(Duration.ofMinutes(30)).getEpochSecond();

            ApiResponse response = postCallback("MTN_MOMO", body,
                    WebhookSignature.sign(MTN_SECRET, old, body), String.valueOf(old));

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("PROVIDER_SIGNATURE_EXPIRED");
        }

        @Test
        @DisplayName("un operateur sans secret configure est refuse, pas laisse passer")
        void providerWithoutSecretIsRejected() {
            // ORANGE_MONEY n'a pas de secret dans cette configuration. Le refus doit avoir
            // lieu quel que soit le contenu de la signature presentee : c'est l'absence de
            // secret cote serveur qui tranche, pas une comparaison.
            //
            // La signature envoyee est donc arbitraire, comme le serait celle d'un
            // attaquant. Tenter d'en calculer une avec un secret vide echouerait d'ailleurs
            // des la construction du HMAC — ce qui montre au passage pourquoi le filtre
            // verifie que le secret est renseigne AVANT de calculer quoi que ce soit.
            String body = callbackBody("evt-" + suffix, "TX-" + suffix, "SUCCEEDED");
            long timestamp = Instant.now().getEpochSecond();

            ApiResponse response = postCallback("ORANGE_MONEY", body,
                    "sha256=1111111111111111111111111111111111111111111111111111111111111111",
                    String.valueOf(timestamp));

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.code()).isEqualTo("PROVIDER_UNKNOWN");
        }

        @Test
        @DisplayName("les tentatives refusees laissent une trace d'audit")
        void rejectionsAreAudited() {
            long before = auditCount("WEBHOOK_REJECTED");

            postCallback("MTN_MOMO", callbackBody("evt-" + suffix, "TX-" + suffix, "SUCCEEDED"),
                    "sha256=deadbeef", String.valueOf(Instant.now().getEpochSecond()));

            // Une signature invalide n'est pas un incident technique a ignorer : c'est
            // soit un operateur mal configure, soit quelqu'un qui essaie.
            assertThat(auditCount("WEBHOOK_REJECTED")).isEqualTo(before + 1);
        }

        @Test
        @DisplayName("la reponse d'erreur ne revele pas quelle verification a echoue")
        void rejectionIsLaconic() {
            ApiResponse response = postCallback("MTN_MOMO",
                    callbackBody("evt-" + suffix, "TX-" + suffix, "SUCCEEDED"),
                    "sha256=deadbeef", String.valueOf(Instant.now().getEpochSecond()));

            // Le detail va dans les journaux et l'audit, pas sur le fil : distinguer
            // finement les causes aiderait autant un attaquant qu'un operateur legitime.
            assertThat(response.body().toString()).doesNotContain(MTN_SECRET);
            assertThat(response.body().get("detail").asText())
                    .isEqualTo("Signature absente, invalide ou expiree");
        }
    }

    @Nested
    @DisplayName("Doublons et rappels tardifs")
    class DuplicatesAndLateCallbacks {

        @Test
        @DisplayName("un rappel rejoue est reconnu et n'a aucun effet")
        void duplicateCallbackHasNoEffect() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);
            String body = callbackBody("evt-" + suffix, externalRef, "SUCCEEDED");

            ApiResponse first = postSignedCallback("MTN_MOMO", body);
            ApiResponse second = postSignedCallback("MTN_MOMO", body);

            assertThat(first.status()).isEqualTo(200);
            assertThat(first.duplicate()).isFalse();

            // Reponse en succes malgre le doublon : repondre en erreur declencherait des
            // retentatives de l'operateur sur une operation deja close.
            assertThat(second.status()).isEqualTo(200);
            assertThat(second.duplicate()).isTrue();

            assertThat(callbackCount("evt-" + suffix)).isEqualTo(1);
            assertThat(outboxEventTypes(operation.transactionId()))
                    .as("un seul evenement, malgre deux rappels")
                    .containsExactly("provider.operation.succeeded");
        }

        @Test
        @DisplayName("un rappel tardif sur une operation deja tranchee est neutralise")
        void lateCallbackOnResolvedOperation() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            // La relance de statut a deja conclu.
            operations.markResolved(operation.id(), OperationStatus.SUCCEEDED,
                    "MTN-REF-" + suffix, null, null, null);

            // L'operateur envoie son rappel des heures plus tard, avec un identifiant neuf.
            // La deduplication ne le voit pas passer : seul le statut definitif l'arrete.
            ApiResponse response = postSignedCallback("MTN_MOMO",
                    callbackBody("evt-tardif-" + suffix, externalRef, "SUCCEEDED"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(outboxEventTypes(operation.transactionId()))
                    .as("aucun evenement ne doit etre republie")
                    .isEmpty();
            assertThat(auditCount("CALLBACK_LATE")).isPositive();
        }

        @Test
        @DisplayName("un rappel pour une reference inconnue est conserve, pas rejete")
        void orphanCallbackIsKept() {
            // Cas reel : l'appel initial a expire, l'operation n'a jamais ete enregistree,
            // mais l'operateur a bien traite la demande et rappelle. Le message brut est
            // conserve — c'est la matiere de la reconciliation.
            ApiResponse response = postSignedCallback("MTN_MOMO",
                    callbackBody("evt-orphelin-" + suffix, "TX-INCONNUE-" + suffix, "SUCCEEDED"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(callbackCount("evt-orphelin-" + suffix)).isEqualTo(1);
            assertThat(auditCount("CALLBACK_ORPHAN")).isPositive();
        }

        @Test
        @DisplayName("un rappel non conclusif ne publie rien")
        void nonConclusiveCallbackPublishesNothing() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            ApiResponse response = postSignedCallback("MTN_MOMO",
                    callbackBody("evt-" + suffix, externalRef, "PENDING"));

            assertThat(response.status()).isEqualTo(200);
            assertThat(statusOf(operation.transactionId())).isEqualTo("ACCEPTED");
            assertThat(outboxEventTypes(operation.transactionId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("Issues")
    class Outcomes {

        @Test
        @DisplayName("un refus de l'operateur publie un echec, et annule les relances")
        void failureIsPublishedAndCancelsPolling() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            ApiResponse response = postSignedCallback("MTN_MOMO", """
                    {"eventId":"evt-%s","externalRef":"%s","providerRef":"MTN-1",\
                    "status":"FAILED","errorCode":"INSUFFICIENT_FUNDS",\
                    "errorMessage":"Solde insuffisant"}""".formatted(suffix, externalRef));

            assertThat(response.status()).isEqualTo(200);
            assertThat(statusOf(operation.transactionId())).isEqualTo("FAILED");
            assertThat(outboxEventTypes(operation.transactionId()))
                    .containsExactly("provider.operation.failed");

            // next_poll_at passe a NULL : c'est ainsi qu'un rappel annule les relances
            // restantes, sans qu'aucun code d'annulation explicite n'existe.
            Boolean stillScheduled = jdbc.sql("""
                            SELECT next_poll_at IS NOT NULL FROM provider.provider_operation
                             WHERE transaction_id = :id
                            """)
                    .param("id", operation.transactionId())
                    .query(Boolean.class).single();
            assertThat(stillScheduled).isFalse();
        }

        @Test
        @DisplayName("l'operation se consulte pour diagnostic")
        void operationIsReadable() {
            String externalRef = "TX-" + suffix;
            ProviderOperation operation = givenPendingOperation(externalRef, OperationStatus.ACCEPTED);

            ApiResponse response = get("/v1/operations/" + operation.transactionId());

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().get("status").asText()).isEqualTo("ACCEPTED");
            assertThat(response.body().get("externalRef").asText()).isEqualTo(externalRef);
            assertThat(response.body().get("pollBudgetExhausted").asBoolean()).isFalse();
        }

        @Test
        @DisplayName("une transaction inconnue produit un 404")
        void unknownOperation() {
            assertThat(get("/v1/operations/" + java.util.UUID.randomUUID()).status()).isEqualTo(404);
        }
    }
}
