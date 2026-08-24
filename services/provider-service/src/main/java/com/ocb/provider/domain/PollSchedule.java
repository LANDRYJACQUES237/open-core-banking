package com.ocb.provider.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Calendrier des relances de statut, et budget au-dela duquel on cesse de demander.
 *
 * <p><b>Pourquoi un budget plutot qu'une relance infinie.</b> Relancer indefiniment
 * transformerait une operation sans reponse en tache de fond eternelle, invisible, qui
 * n'appellerait jamais de decision humaine. Le budget force la question : au bout de
 * vingt-quatre heures, quelqu'un doit trancher. C'est le passage a
 * {@link OperationStatus#UNRESOLVED}.
 *
 * <p><b>Pourquoi un recul exponentiel puis un palier.</b> Les premieres secondes sont
 * celles ou la reponse a le plus de chances d'arriver : un client qui approuve sur son
 * telephone le fait en general en moins d'une minute. Passe ce delai, insister toutes les
 * cinq secondes ne fait que consommer le quota d'appels de l'operateur — quota qui sera
 * necessaire aux transactions en cours. Le palier horaire couvre le cas de la panne
 * prolongee, ou seule la duree compte.
 *
 * <p>La classe est pure : aucune horloge interne, aucun acces a la base. L'instant courant
 * est fourni par l'appelant, ce qui permet de tester vingt-quatre heures de relances en
 * quelques microsecondes plutot que de les attendre.
 */
public record PollSchedule(Duration budget) {

    /**
     * Delais successifs avant chaque relance. Au-dela du dernier, le palier s'applique.
     */
    public static final List<Duration> STEPS = List.of(
            Duration.ofSeconds(5),
            Duration.ofSeconds(15),
            Duration.ofSeconds(45),
            Duration.ofMinutes(2),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15));

    public static final Duration STEADY_INTERVAL = Duration.ofHours(1);
    public static final Duration DEFAULT_BUDGET = Duration.ofHours(24);

    public PollSchedule {
        if (budget == null || budget.isNegative() || budget.isZero()) {
            throw new IllegalArgumentException("Le budget de relance doit etre strictement positif");
        }
    }

    public static PollSchedule standard() {
        return new PollSchedule(DEFAULT_BUDGET);
    }

    /**
     * Instant de la prochaine relance.
     *
     * @param startedAt        premiere emission de la demande
     * @param pollAttemptsSoFar relances deja effectuees, zero pour la premiere
     * @param now              instant courant
     * @return l'instant de la prochaine relance, ou vide si le budget est epuise
     */
    public Optional<Instant> nextPollAt(Instant startedAt, int pollAttemptsSoFar, Instant now) {
        Duration delay = delayBefore(pollAttemptsSoFar);
        Instant candidate = now.plus(delay);

        // Le budget se mesure depuis la PREMIERE emission, pas depuis la derniere relance.
        // Sinon une operation qui accumule les paliers horaires repousserait sa propre
        // echeance indefiniment, et le budget ne bornerait plus rien.
        if (candidate.isAfter(startedAt.plus(budget))) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    public Duration delayBefore(int pollAttemptsSoFar) {
        if (pollAttemptsSoFar < 0) {
            throw new IllegalArgumentException("Le nombre de relances ne peut pas etre negatif");
        }
        return pollAttemptsSoFar < STEPS.size() ? STEPS.get(pollAttemptsSoFar) : STEADY_INTERVAL;
    }

    public boolean exhausted(Instant startedAt, int pollAttemptsSoFar, Instant now) {
        return nextPollAt(startedAt, pollAttemptsSoFar, now).isEmpty();
    }
}
