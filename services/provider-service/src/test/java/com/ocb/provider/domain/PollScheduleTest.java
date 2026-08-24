package com.ocb.provider.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Le calendrier de relance, verifie sans attendre.
 *
 * <p>{@link PollSchedule} est une fonction pure : l'instant courant lui est fourni. Vingt-
 * quatre heures de relances se simulent donc en quelques microsecondes. Un ordonnanceur
 * qui lirait l'horloge lui-meme ne pourrait etre teste qu'en attendant reellement, ce qui
 * reviendrait a ne pas le tester.
 */
class PollScheduleTest {

    private static final Instant START = Instant.parse("2026-08-24T10:00:00Z");

    @Test
    @DisplayName("le recul suit les paliers declares, puis passe a l'intervalle horaire")
    void backoffFollowsTheDeclaredSteps() {
        PollSchedule schedule = PollSchedule.standard();

        assertThat(schedule.delayBefore(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(schedule.delayBefore(1)).isEqualTo(Duration.ofSeconds(15));
        assertThat(schedule.delayBefore(2)).isEqualTo(Duration.ofSeconds(45));
        assertThat(schedule.delayBefore(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(schedule.delayBefore(4)).isEqualTo(Duration.ofMinutes(5));
        assertThat(schedule.delayBefore(5)).isEqualTo(Duration.ofMinutes(15));

        // Au-dela, le palier. Continuer a doubler donnerait des intervalles de plusieurs
        // jours, bien au-dela du budget, et la relance deviendrait purement theorique.
        assertThat(schedule.delayBefore(6)).isEqualTo(Duration.ofHours(1));
        assertThat(schedule.delayBefore(50)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("les premieres relances sont rapprochees : c'est la que la reponse arrive")
    void earlyAttemptsAreClose() {
        PollSchedule schedule = PollSchedule.standard();

        // Un client qui approuve sur son telephone le fait en general en moins d'une
        // minute. Les quatre premieres relances doivent tenir dans cette fenetre.
        Duration firstFour = schedule.delayBefore(0)
                .plus(schedule.delayBefore(1))
                .plus(schedule.delayBefore(2))
                .plus(schedule.delayBefore(3));

        assertThat(firstFour).isLessThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("le budget se mesure depuis la premiere emission, pas depuis la derniere relance")
    void budgetIsMeasuredFromTheFirstAttempt() {
        // Le piege que ce test verrouille : si le budget etait compte depuis la derniere
        // relance, une operation accumulant les paliers horaires repousserait sa propre
        // echeance a chaque tentative et ne serait jamais abandonnee. Le budget ne
        // bornerait plus rien.
        PollSchedule schedule = new PollSchedule(Duration.ofHours(2));

        Instant longAfterStart = START.plus(Duration.ofHours(1).plusMinutes(50));
        Optional<Instant> next = schedule.nextPollAt(START, 10, longAfterStart);

        assertThat(next)
                .as("une relance horaire depasserait le budget de deux heures")
                .isEmpty();
    }

    @Test
    @DisplayName("le budget finit par s'epuiser, et en un nombre fini de tentatives")
    void budgetEventuallyRunsOut() {
        PollSchedule schedule = PollSchedule.standard();

        List<Instant> attempts = new ArrayList<>();
        Instant now = START;
        int guard = 0;

        // On deroule le calendrier complet, sans jamais attendre.
        while (guard++ < 1000) {
            Optional<Instant> next = schedule.nextPollAt(START, attempts.size(), now);
            if (next.isEmpty()) {
                break;
            }
            attempts.add(next.get());
            now = next.get();
        }

        assertThat(guard).as("le calendrier doit se terminer").isLessThan(1000);

        // Un ordre de grandeur exploitable : assez de tentatives pour couvrir une panne
        // prolongee, assez peu pour ne pas noyer l'operateur.
        assertThat(attempts.size()).isBetween(20, 40);

        Instant last = attempts.getLast();
        assertThat(Duration.between(START, last))
                .as("la derniere relance reste dans le budget")
                .isLessThanOrEqualTo(PollSchedule.DEFAULT_BUDGET);
    }

    @Test
    @DisplayName("une relance programmee tombe toujours apres l'instant courant")
    void nextPollIsAlwaysInTheFuture() {
        PollSchedule schedule = PollSchedule.standard();
        Instant now = START.plus(Duration.ofMinutes(30));

        for (int attempts = 0; attempts < 6; attempts++) {
            Optional<Instant> next = schedule.nextPollAt(START, attempts, now);
            assertThat(next).isPresent();
            assertThat(next.get()).isAfter(now);
        }
    }

    @Test
    @DisplayName("un budget nul ou negatif est refuse a la construction")
    void rejectsInvalidBudget() {
        assertThatThrownBy(() -> new PollSchedule(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollSchedule(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollSchedule(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un nombre de relances negatif est refuse")
    void rejectsNegativeAttempts() {
        assertThatThrownBy(() -> PollSchedule.standard().delayBefore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("exhausted et nextPollAt disent la meme chose")
    void exhaustedAgreesWithNextPollAt() {
        PollSchedule schedule = new PollSchedule(Duration.ofMinutes(10));

        assertThat(schedule.exhausted(START, 0, START)).isFalse();
        assertThat(schedule.nextPollAt(START, 0, START)).isPresent();

        Instant tooLate = START.plus(Duration.ofMinutes(9));
        assertThat(schedule.exhausted(START, 8, tooLate)).isTrue();
        assertThat(schedule.nextPollAt(START, 8, tooLate)).isEmpty();
    }
}
