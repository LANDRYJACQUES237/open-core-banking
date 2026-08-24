package com.ocb.payment.adapter.ledger;

import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;

import java.io.IOException;

/**
 * Attache un jeton de service a chaque appel sortant vers le grand livre.
 *
 * <p>Le gestionnaire de clients autorises met le jeton en cache et le renouvelle avant
 * expiration : il n'y a donc pas un aller-retour vers le fournisseur d'identite par
 * ecriture comptable. C'est la raison d'utiliser l'infrastructure de Spring plutot que
 * d'ecrire soi-meme un appel au point de terminaison de jetons — le cache, le
 * renouvellement anticipe et la surete vis-a-vis des threads sont exactement les endroits
 * ou une implementation maison se trompe.
 *
 * <p>Si le jeton ne peut pas etre obtenu, l'appel n'est pas tente. Emettre une requete sans
 * jeton produirait un 401, que le client traduirait en refus du grand livre — donc en
 * conclusion erronee. Mieux vaut une panne franche.
 */
public class ClientCredentialsInterceptor implements ClientHttpRequestInterceptor {

    private final OAuth2AuthorizedClientManager manager;
    private final String registrationId;
    private final String principal;

    public ClientCredentialsInterceptor(OAuth2AuthorizedClientManager manager,
                                        String registrationId,
                                        String principal) {
        this.manager = manager;
        this.registrationId = registrationId;
        this.principal = principal;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        OAuth2AuthorizedClient client = manager.authorize(
                OAuth2AuthorizeRequest.withClientRegistrationId(registrationId)
                        .principal(principal)
                        .build());

        if (client == null || client.getAccessToken() == null) {
            throw new IllegalStateException(
                    "Aucun jeton de service obtenu pour %s : appel au grand livre abandonne"
                            .formatted(registrationId));
        }

        request.getHeaders().setBearerAuth(client.getAccessToken().getTokenValue());
        return execution.execute(request, body);
    }
}
