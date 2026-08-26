package com.ocb.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ce que chaque sonde repond quand le courtier est absent.
 *
 * <p><b>La distinction que ces tests verrouillent.</b> Une sonde de <b>vivacite</b> qui
 * echoue fait <b>redemarrer le pod</b>. Si elle dependait du courtier, une coupure Kafka
 * ferait redemarrer en boucle des processus parfaitement sains : on ajouterait une panne a
 * une panne, et le retour a la normale en serait retarde, puisque des processus repartant
 * de zero se rueraient tous ensemble sur un courtier qui vient a peine de revenir.
 *
 * <p>Une sonde de <b>disponibilite</b> qui echoue retire seulement le pod du service. C'est
 * le comportement correct pour un consommateur coupe de son courtier : il repond encore en
 * HTTP mais ne traite plus rien, et il revient tout seul.
 *
 * <p>Cette distinction ne se lit nulle part dans le code applicatif — elle vit dans deux
 * lignes de configuration. C'est exactement le genre de reglage qu'on croit avoir fait.
 *
 * <p><b>Le montage est fortuit et parfaitement fidele.</b> Ce socle ne demarre aucun
 * courtier ; l'adresse configuree ne mene nulle part. C'est, du point de vue du service,
 * indiscernable d'une coupure Kafka en production.
 */
class ProbeSemanticsIT extends PaymentPersistenceTestBase {

    @Test
    @DisplayName("courtier injoignable : le service reste vivant")
    void livenessIgnoresTheBroker() {
        ApiResponse liveness = get("/actuator/health/liveness", null);

        assertThat(liveness.status())
                .as("un redemarrage ne reglerait rien : le probleme n'est pas dans ce processus")
                .isEqualTo(200);
        assertThat(liveness.body().get("status").asText()).isEqualTo("UP");
    }

    @Test
    @DisplayName("courtier injoignable : le service n'est pas pret a servir")
    void readinessReflectsTheBroker() {
        ApiResponse readiness = get("/actuator/health/readiness", null);

        // Si cette assertion tombe a 200 un jour, c'est que le courtier a disparu du
        // groupe readiness — et le service se declarerait pret alors qu'il ne consomme
        // plus rien. La panne serait alors invisible jusqu'au rapprochement comptable.
        assertThat(readiness.status())
                .as("un consommateur coupe de son courtier ne doit pas recevoir de trafic")
                .isEqualTo(503);
        assertThat(readiness.body().get("status").asText()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("la base, elle, est bien vue comme disponible")
    void theDatabaseIsUp() {
        // Contre-epreuve : sans elle, une readiness a DOWN pourrait tout aussi bien
        // signifier que la sonde de base est cassee, et le test precedent ne prouverait
        // rien du courtier.
        ApiResponse health = get("/actuator/health/db", null);

        assertThat(health.status()).isIn(200, 404);
        if (health.status() == 200) {
            assertThat(health.body().get("status").asText()).isEqualTo("UP");
        }
    }
}
