package com.ocb.ledger;

import com.ocb.platform.security.OcbScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Les regles d'acces au grand livre, exercees refus par refus.
 *
 * <p>Chaque test correspond a une maniere concrete dont un jeton peut etre illegitime.
 * Verifier seulement le cas nominal — un jeton valide fonctionne — ne prouverait rien :
 * une configuration qui accepterait tout le ferait passer aussi.
 */
class LedgerSecurityIT extends LedgerIntegrationTestBase {

    private static final String ENTRY_BODY = """
            {"description":"tentative","lines":[
              {"accountNumber":"1100","direction":"DR","amount":"100","currency":"XAF"},
              {"accountNumber":"4100","direction":"CR","amount":"100","currency":"XAF"}]}""";

    @Nested
    @DisplayName("Authentification")
    class Authentication {

        @Test
        @DisplayName("sans jeton, l'ecriture est refusee")
        void noTokenIsRejected() {
            ApiResponse response = post("/v1/journal-entries", "sec-" + suffix, ENTRY_BODY, null);
            assertThat(response.status()).isEqualTo(401);
        }

        @Test
        @DisplayName("sans jeton, meme la lecture est refusee")
        void noTokenIsRejectedOnReads() {
            assertThat(get("/v1/accounts/1100", null).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("un jeton expire est refuse")
        void expiredTokenIsRejected() {
            String expired = jwtIssuer.expiredToken("payment-service", "ledger-service",
                    OcbScopes.LEDGER_READ, OcbScopes.LEDGER_POST);

            assertThat(get("/v1/accounts/1100", expired).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("un jeton signe par une autre cle est refuse")
        void foreignSignatureIsRejected() {
            // Le contenu est irreprochable : bon emetteur, bonne audience, bonnes portees.
            // Seule la cle de signature differe. C'est le scenario du jeton fabrique.
            String forged = jwtIssuer.tokenSignedByAnotherKey("payment-service", "ledger-service",
                    OcbScopes.LEDGER_READ, OcbScopes.LEDGER_POST);

            assertThat(get("/v1/accounts/1100", forged).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("un jeton d'un autre emetteur est refuse")
        void otherIssuerIsRejected() {
            String elsewhere = jwtIssuer.tokenFromOtherIssuer("payment-service", "ledger-service",
                    OcbScopes.LEDGER_READ);

            assertThat(get("/v1/accounts/1100", elsewhere).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("un jeton emis pour un autre service est refuse")
        void wrongAudienceIsRejected() {
            // Signature valide, emetteur valide, portees valides — mais le jeton n'a pas
            // ete emis pour ce service. Sans verification d'audience, un service compromis
            // pourrait rejouer contre ses voisins les jetons qu'il recoit.
            String elsewhere = jwtIssuer.tokenForOtherAudience("console", OcbScopes.LEDGER_READ);

            assertThat(get("/v1/accounts/1100", elsewhere).status()).isEqualTo(401);
        }

        @Test
        @DisplayName("un jeton illisible est refuse sans faire tomber le service")
        void malformedTokenIsRejected() {
            assertThat(get("/v1/accounts/1100", "ceci-n-est-pas-un-jeton").status()).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Autorisation par portee")
    class Scopes {

        @Test
        @DisplayName("lire ne suffit pas pour ecrire")
        void readScopeCannotPost() {
            // La separation lecture/ecriture est la raison d'etre de deux portees
            // distinctes : une console de supervision lit les soldes, elle ne doit jamais
            // pouvoir en creer.
            String readOnly = jwtIssuer.token("console", "ledger-service", OcbScopes.LEDGER_READ);

            ApiResponse response = post("/v1/journal-entries", "sec-scope-" + suffix,
                    ENTRY_BODY, readOnly);

            assertThat(response.status())
                    .as("authentifie mais pas autorise : 403, pas 401")
                    .isEqualTo(403);
        }

        @Test
        @DisplayName("ecrire ne suffit pas pour lire")
        void postScopeCannotRead() {
            String writeOnly = jwtIssuer.token("payment-service", "ledger-service",
                    OcbScopes.LEDGER_POST);

            assertThat(get("/v1/accounts/1100", writeOnly).status()).isEqualTo(403);
        }

        @Test
        @DisplayName("une portee d'un autre service ne donne aucun droit ici")
        void foreignScopeGrantsNothing() {
            // Un client marchand porteur de payment:initiate ne doit pas pouvoir ecrire
            // au grand livre : ce serait contourner la machine a etats et le calcul des
            // frais, et pouvoir se crediter soi-meme.
            String merchant = jwtIssuer.token("marchand", "ledger-service",
                    OcbScopes.PAYMENT_INITIATE, OcbScopes.PAYMENT_READ);

            ApiResponse response = post("/v1/journal-entries", "sec-foreign-" + suffix,
                    ENTRY_BODY, merchant);

            assertThat(response.status()).isEqualTo(403);
        }

        @Test
        @DisplayName("ouvrir un compte demande la portee d'ecriture")
        void openingAnAccountRequiresPost() {
            String readOnly = jwtIssuer.token("console", "ledger-service", OcbScopes.LEDGER_READ);

            ApiResponse response = post("/v1/accounts", "sec-account-" + suffix, """
                    {"accountNumber":"2100.sec-%s","accountType":"LIABILITY","currency":"XAF"}
                    """.formatted(suffix), readOnly);

            assertThat(response.status()).isEqualTo(403);
        }

        @Test
        @DisplayName("avec les bonnes portees, l'operation aboutit")
        void correctScopesWork() {
            // Contre-preuve indispensable : sans elle, une configuration qui refuserait
            // tout ferait passer tous les tests precedents.
            String wallet = openWallet("secok");
            ApiResponse response = postEntry("sec-ok-" + suffix, "ecriture autorisee",
                    line("1100", "DR", "1000"), line(wallet, "CR", "1000"));

            assertThat(response.status()).isEqualTo(201);
        }
    }

    @Nested
    @DisplayName("Sondes")
    class Probes {

        @Test
        @DisplayName("les sondes de sante restent accessibles sans jeton")
        void healthProbesStayOpen() {
            // Kubernetes n'a pas de jeton. Une sonde protegee rendrait le pod
            // perpetuellement non pret, et le deploiement echouerait sans que la cause
            // soit evidente.
            assertThat(get("/actuator/health/liveness", null).status()).isEqualTo(200);

            // Ce service ne consomme aucun message : sa readiness ne verifie que la base,
            // qui tourne. Elle repond donc 200 ici, contrairement aux trois consommateurs.
            assertThat(get("/actuator/health/readiness", null).status()).isEqualTo(200);
        }

        @Test
        @DisplayName("les autres points d'actuator ne sont pas ouverts")
        void otherActuatorEndpointsAreNotOpen() {
            // Seules les sondes sont ouvertes. Les metriques revelent des volumes
            // d'activite, ce qui n'a pas a etre public.
            assertThat(get("/actuator/metrics", null).status()).isNotEqualTo(200);
        }
    }
}
