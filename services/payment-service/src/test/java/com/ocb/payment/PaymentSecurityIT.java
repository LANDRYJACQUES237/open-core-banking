package com.ocb.payment;

import com.ocb.platform.security.OcbScopes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Qui peut appeler ce service, et ce que son identite entraine.
 *
 * <p>La premiere moitie de ces tests verifie le controle d'acces. La seconde verifie une
 * consequence moins evidente de l'authentification : l'identite de l'appelant <b>fait
 * partie de la cle d'idempotence</b>. C'est ce qui empeche deux clients distincts de se
 * voler mutuellement leurs reponses en choisissant la meme cle.
 */
class PaymentSecurityIT extends PaymentPersistenceTestBase {

    private static final String AUDIENCE = "payment-service";

    // --- Controle d'acces ---------------------------------------------------------------

    @Test
    @DisplayName("sans jeton, aucune demande d'encaissement n'est prise en charge")
    void noTokenIsRejected() {
        ApiResponse response = post("/v1/collections", "idem-" + suffix, collectionBody("10000"), null);

        assertThat(response.status()).isEqualTo(401);
        assertThat(countTransactions("TX-" + suffix))
                .as("un appel refuse ne doit rien laisser en base")
                .isZero();
    }

    @Test
    @DisplayName("un jeton expire est refuse")
    void expiredTokenIsRejected() {
        String expired = jwtIssuer.expiredToken("marchand", AUDIENCE, OcbScopes.PAYMENT_INITIATE);

        assertThat(post("/v1/collections", "idem-" + suffix, collectionBody("10000"), expired)
                .status()).isEqualTo(401);
    }

    @Test
    @DisplayName("un jeton signe par une autre cle est refuse")
    void foreignSignatureIsRejected() {
        // Le cas qui compte vraiment : la charge utile est parfaitement formee — bon
        // emetteur, bonne audience, bonnes portees — et seule la signature ne correspond
        // pas. C'est ce que produit un attaquant qui recopie la structure d'un jeton
        // legitime.
        String forged = jwtIssuer.tokenSignedByAnotherKey(
                "marchand", AUDIENCE, OcbScopes.PAYMENT_INITIATE);

        assertThat(post("/v1/collections", "idem-" + suffix, collectionBody("10000"), forged)
                .status()).isEqualTo(401);
    }

    @Test
    @DisplayName("un jeton emis pour un autre service est refuse")
    void wrongAudienceIsRejected() {
        // Sans validation d'audience, un jeton legitimement emis pour une console
        // d'administration passerait ici avec toutes ses portees.
        String elsewhere = jwtIssuer.tokenForOtherAudience("marchand", OcbScopes.PAYMENT_INITIATE);

        assertThat(post("/v1/collections", "idem-" + suffix, collectionBody("10000"), elsewhere)
                .status()).isEqualTo(401);
    }

    @Test
    @DisplayName("lire ne donne pas le droit d'encaisser")
    void readScopeCannotInitiate() {
        String readOnly = jwtIssuer.token("observateur", AUDIENCE, OcbScopes.PAYMENT_READ);

        assertThat(post("/v1/collections", "idem-" + suffix, collectionBody("10000"), readOnly)
                .status()).isEqualTo(403);
        assertThat(countTransactions("TX-" + suffix)).isZero();
    }

    @Test
    @DisplayName("la portee du grand livre ne vaut rien ici")
    void foreignScopeGrantsNothing() {
        // Une portee reconnue par la plateforme mais etrangere a ce service. Verifier
        // qu'elle ne donne rien protege contre une regle ecrite avec hasAnyAuthority trop
        // large.
        String ledgerScopes = jwtIssuer.token("grand-livre", AUDIENCE,
                OcbScopes.LEDGER_POST, OcbScopes.LEDGER_READ);

        assertThat(post("/v1/collections", "idem-" + suffix, collectionBody("10000"), ledgerScopes)
                .status()).isEqualTo(403);
    }

    @Test
    @DisplayName("les sondes de sante restent accessibles sans jeton")
    void healthProbesStayOpen() {
        // Kubernetes ne presente pas de jeton. Fermer ces points d'entree rendrait le
        // service impossible a demarrer en cluster.
        assertThat(get("/actuator/health/liveness", null).status()).isEqualTo(200);
        assertThat(get("/actuator/health/readiness", null).status()).isEqualTo(200);
    }

    @Test
    @DisplayName("les metriques ne sont pas ouvertes pour autant")
    void metricsAreNotOpen() {
        // L'ouverture porte sur les sondes, pas sur tout l'actuator : le volume et le
        // montant des transactions se lisent dans les metriques.
        assertThat(get("/actuator/metrics", null).status()).isIn(401, 403, 404);
    }

    // --- L'identite comme portee d'idempotence -------------------------------------------

    @Test
    @DisplayName("deux marchands peuvent choisir la meme cle d'idempotence sans se collisionner")
    void idempotencyKeysAreScopedPerCaller() {
        // Le scenario reel : les cles d'idempotence sont choisies par le client, et rien
        // n'empeche un client d'utiliser un compteur plutot qu'un identifiant aleatoire.
        // « paiement-1 » sera donc choisi par plusieurs marchands.
        String sharedKey = "compteur-1-" + suffix;

        String first = jwtIssuer.token("marchand-A", AUDIENCE, OcbScopes.PAYMENT_INITIATE);
        String second = jwtIssuer.token("marchand-B", AUDIENCE, OcbScopes.PAYMENT_INITIATE);

        ApiResponse a = post("/v1/collections", sharedKey, collectionBody("10000"), first);
        ApiResponse b = post("/v1/collections", sharedKey, collectionBody("10000"), second);

        assertThat(a.status()).isEqualTo(202);
        assertThat(b.status())
                .as("la demande du second marchand est nouvelle, pas un rejeu")
                .isEqualTo(202);
        assertThat(b.transactionId())
                .as("sinon le marchand B recevrait la transaction du marchand A et croirait "
                        + "sa demande prise en charge alors qu'elle aurait ete ignoree")
                .isNotEqualTo(a.transactionId());

        assertThat(scopesForKey(sharedKey))
                .as("la meme cle existe deux fois, une par appelant")
                .containsExactlyInAnyOrder("marchand-A", "marchand-B");
    }

    @Test
    @DisplayName("le meme marchand qui rejoue sa cle retrouve sa transaction")
    void sameCallerStillReplays() {
        // Contre-epreuve indispensable : le test precedent passerait tout aussi bien si
        // l'idempotence avait purement et simplement cesse de fonctionner. Ici, seul le
        // sujet est identique — c'est donc bien lui qui distingue les deux cas.
        String key = "compteur-2-" + suffix;
        String merchant = jwtIssuer.token("marchand-A", AUDIENCE, OcbScopes.PAYMENT_INITIATE);

        ApiResponse first = post("/v1/collections", key, collectionBody("10000"), merchant);
        ApiResponse replay = post("/v1/collections", key, collectionBody("10000"), merchant);

        assertThat(first.status()).isEqualTo(202);
        assertThat(replay.status())
                .as("200 et non 202 : rien de nouveau n'a ete cree")
                .isEqualTo(200);
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());
    }

    @Test
    @DisplayName("un marchand ne rejoue pas la cle d'un autre en changeant le montant")
    void mismatchIsScopedToo() {
        // Sans cloisonnement, cet appel remonterait un conflit de charge utile — le
        // marchand B apprendrait ainsi qu'une transaction du marchand A existe sous cette
        // cle, et qu'elle porte un autre montant. Une fuite d'information par message
        // d'erreur.
        String sharedKey = "compteur-3-" + suffix;

        String first = jwtIssuer.token("marchand-A", AUDIENCE, OcbScopes.PAYMENT_INITIATE);
        String second = jwtIssuer.token("marchand-B", AUDIENCE, OcbScopes.PAYMENT_INITIATE);

        post("/v1/collections", sharedKey, collectionBody("10000"), first);
        ApiResponse other = post("/v1/collections", sharedKey, collectionBody("25000"), second);

        assertThat(other.status())
                .as("aucun conflit ne doit remonter : ces deux cles ne sont pas la meme")
                .isEqualTo(202);
    }

    // --- Observation ---------------------------------------------------------------------

    private long countTransactions(String externalRef) {
        Long count = jdbc.sql("""
                        SELECT COUNT(*) FROM payment.payment_transaction WHERE external_ref = :ref
                        """)
                .param("ref", externalRef)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    private java.util.List<String> scopesForKey(String key) {
        return jdbc.sql("SELECT scope FROM payment.idempotency_record WHERE key = :key")
                .param("key", key)
                .query(String.class)
                .list();
    }
}
