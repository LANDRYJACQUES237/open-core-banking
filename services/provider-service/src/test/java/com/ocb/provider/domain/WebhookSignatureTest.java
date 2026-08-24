package com.ocb.provider.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verification de la signature des rappels entrants.
 *
 * <p>C'est la seule surface publique de la plateforme : ce qui arrive ici vient
 * d'Internet. Chaque test correspond a une attaque precise, pas a un cas limite theorique.
 */
class WebhookSignatureTest {

    private static final String SECRET = "secret-partage-avec-l-operateur";
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final Instant NOW = Instant.parse("2026-08-24T10:00:00Z");
    private static final String BODY = """
            {"eventId":"evt-1","externalRef":"TX-1","status":"SUCCEEDED","fee":"150"}""";

    @Test
    @DisplayName("une signature legitime est acceptee")
    void acceptsAGenuineSignature() {
        long timestamp = NOW.getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, timestamp, BODY);

        assertThat(verify(signature, timestamp, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.VALID);
    }

    @Test
    @DisplayName("un corps altere invalide la signature")
    void detectsTamperedBody() {
        long timestamp = NOW.getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, timestamp, BODY);

        String tampered = BODY.replace("\"fee\":\"150\"", "\"fee\":\"15000\"");

        assertThat(verify(signature, timestamp, tampered, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
    }

    @Test
    @DisplayName("la signature porte sur les octets bruts, pas sur le JSON equivalent")
    void signatureCoversRawBytesNotSemantics() {
        // Demonstration de pourquoi le corps brut doit etre conserve. Ces deux corps sont
        // le meme document JSON — memes champs, memes valeurs — mais pas les memes octets.
        // Verifier la signature sur une representation reserialisee par Jackson echouerait
        // donc sur des rappels parfaitement legitimes.
        long timestamp = NOW.getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, timestamp, BODY);

        String reserialized = """
                {"eventId": "evt-1", "externalRef": "TX-1", "status": "SUCCEEDED", "fee": "150"}""";

        assertThat(verify(signature, timestamp, reserialized, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
    }

    @Test
    @DisplayName("modifier l'horodatage invalide la signature")
    void timestampIsPartOfTheSignature() {
        // L'horodatage entre dans le calcul : sans cela, un attaquant pourrait rafraichir
        // une capture ancienne en changeant simplement l'en-tete, et rejouer indefiniment
        // une signature valide.
        long timestamp = NOW.getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, timestamp, BODY);

        assertThat(verify(signature, timestamp + 1, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
    }

    @Test
    @DisplayName("une signature capturee expire, meme si elle reste correcte")
    void rejectsReplayOutsideTheWindow() {
        // Le scenario : quelqu'un capture une requete legitime et la rejoue plus tard.
        // La signature est authentique, mais la fenetre borne sa valeur a quelques minutes.
        long oldTimestamp = NOW.minus(Duration.ofMinutes(10)).getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, oldTimestamp, BODY);

        assertThat(verify(signature, oldTimestamp, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.EXPIRED);
    }

    @Test
    @DisplayName("la fenetre est symetrique : une horloge operateur peut avancer")
    void windowToleratesClockSkewBothWays() {
        long slightlyAhead = NOW.plus(Duration.ofMinutes(2)).getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, slightlyAhead, BODY);

        // Refuser tout horodatage futur rendrait le service dependant d'une
        // synchronisation parfaite entre deux systemes qu'on ne controle pas.
        assertThat(verify(signature, slightlyAhead, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.VALID);

        long farAhead = NOW.plus(Duration.ofMinutes(10)).getEpochSecond();
        assertThat(verify(WebhookSignature.sign(SECRET, farAhead, BODY), farAhead, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.EXPIRED);
    }

    @Test
    @DisplayName("un secret different invalide la signature")
    void wrongSecretIsRejected() {
        long timestamp = NOW.getEpochSecond();
        String signature = WebhookSignature.sign("un-autre-secret", timestamp, BODY);

        assertThat(verify(signature, timestamp, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
    }

    @Test
    @DisplayName("des en-tetes absents ou illisibles sont refuses, sans exception")
    void missingHeadersAreRejected() {
        long timestamp = NOW.getEpochSecond();
        String signature = WebhookSignature.sign(SECRET, timestamp, BODY);

        assertThat(WebhookSignature.verify(null, String.valueOf(timestamp), BODY, SECRET, WINDOW, NOW))
                .isEqualTo(WebhookSignature.Verdict.MISSING);
        assertThat(WebhookSignature.verify(signature, null, BODY, SECRET, WINDOW, NOW))
                .isEqualTo(WebhookSignature.Verdict.MISSING);
        assertThat(WebhookSignature.verify(signature, "pas-un-nombre", BODY, SECRET, WINDOW, NOW))
                .isEqualTo(WebhookSignature.Verdict.MISSING);
        assertThat(WebhookSignature.verify(signature, String.valueOf(timestamp), null, SECRET, WINDOW, NOW))
                .isEqualTo(WebhookSignature.Verdict.MISSING);
    }

    @Test
    @DisplayName("une signature vide ou tronquee est refusee")
    void malformedSignatureIsRejected() {
        long timestamp = NOW.getEpochSecond();
        String genuine = WebhookSignature.sign(SECRET, timestamp, BODY);

        assertThat(verify("", timestamp, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
        assertThat(verify(genuine.substring(0, genuine.length() - 4), timestamp, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
        assertThat(verify(genuine.replace("sha256=", ""), timestamp, BODY, SECRET, NOW))
                .isEqualTo(WebhookSignature.Verdict.INVALID);
    }

    @Test
    @DisplayName("la signature est stable : le meme corps et le meme horodatage donnent le meme resultat")
    void signingIsDeterministic() {
        long timestamp = NOW.getEpochSecond();
        assertThat(WebhookSignature.sign(SECRET, timestamp, BODY))
                .isEqualTo(WebhookSignature.sign(SECRET, timestamp, BODY));
    }

    private WebhookSignature.Verdict verify(String signature, long timestamp, String body,
                                            String secret, Instant now) {
        return WebhookSignature.verify(signature, String.valueOf(timestamp), body, secret, WINDOW, now);
    }
}
