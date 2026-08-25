package com.ocb.provider.adapter.operator;

import com.fasterxml.jackson.databind.JsonNode;
import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.OperationType;
import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.port.ProviderClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Client HTTP des operateurs.
 *
 * <p>C'est ici que la doctrine du service devient concrete. La classification des reponses
 * n'est pas une question de style :
 *
 * <table border="1">
 *   <tr><th>Ce qui arrive</th><th>Interpretation</th></tr>
 *   <tr><td>2xx avec un statut</td><td>Une reponse. On la prend telle quelle</td></tr>
 *   <tr><td>4xx</td><td>Une reponse : l'operateur a examine et refuse. Rien n'a bouge</td></tr>
 *   <tr><td>5xx</td><td><b>Pas</b> une reponse. L'operateur n'a pas su nous dire</td></tr>
 *   <tr><td>Delai depasse, connexion refusee</td><td><b>Pas</b> une reponse</td></tr>
 * </table>
 *
 * <p>Les deux dernieres lignes remontent en {@link ProviderUnavailableException}. Confondre
 * un 503 avec un refus reviendrait a declarer perdu un paiement peut-etre abouti.
 *
 * <p>Les delais de connexion et de lecture sont distincts, et c'est intentionnel. Ne pas
 * pouvoir ouvrir une connexion en deux secondes signale un operateur injoignable ; mettre
 * quinze secondes a repondre signale un operateur lent mais vivant, ce qui n'appelle pas
 * la meme patience.
 */
@Component
public class HttpProviderClient implements ProviderClient {

    private static final Logger log = LoggerFactory.getLogger(HttpProviderClient.class);

    private final RestTemplate restTemplate;
    private final OperatorEndpoints endpoints;

    public HttpProviderClient(RestTemplateBuilder builder, OperatorEndpoints endpoints) {
        this.endpoints = endpoints;
        this.restTemplate = builder
                .connectTimeout(endpoints.getConnectTimeout())
                .readTimeout(endpoints.getReadTimeout())
                .build();
    }

    @Override
    public ProviderStatus initiateCollection(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalRef", request.externalRef());
        body.put("amount", request.amount().toPlainString());
        body.put("currency", request.amount().currencyCode());
        body.put("payerMsisdn", request.payerMsisdn());
        body.put("callbackUrl", request.callbackUrl());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Sans cette cle, une retentative apres delai depasse creerait un second paiement
        // chez l'operateur. C'est la contrepartie indispensable du fait qu'on retente.
        headers.set("Idempotency-Key", request.idempotencyKey());

        String url = endpoints.baseUrlFor(request.providerCode()) + "/collections";
        return call(request.providerCode(), () ->
                restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class));
    }

    @Override
    public ProviderStatus initiateDisbursement(DisbursementRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("externalRef", request.externalRef());
        body.put("amount", request.amount().toPlainString());
        body.put("currency", request.amount().currencyCode());
        body.put("payeeMsisdn", request.payeeMsisdn());
        body.put("callbackUrl", request.callbackUrl());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // Encore plus critique que pour un encaissement : sans cette cle, une retentative
        // apres delai depasse enverrait l'argent une seconde fois.
        headers.set("Idempotency-Key", request.idempotencyKey());

        String url = endpoints.baseUrlFor(request.providerCode()) + "/disbursements";
        return call(request.providerCode(), () ->
                restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class));
    }

    @Override
    public ProviderStatus pollStatus(ProviderCode providerCode, OperationType type,
                                     String externalRef, String providerRef) {
        // Interrogation par NOTRE reference, pas par celle de l'operateur : quand l'appel
        // initial a expire, aucune reference operateur n'a jamais ete recue, et c'est
        // precisement dans ce cas qu'il faut pouvoir demander.
        String url = endpoints.baseUrlFor(providerCode)
                + resourceOf(type) + "/" + externalRef + "/status";
        return call(providerCode, () ->
                restTemplate.exchange(url, HttpMethod.GET, HttpEntity.EMPTY, JsonNode.class));
    }

    /** Chaque sens a son point d'entree chez l'operateur, y compris pour la relance. */
    private static String resourceOf(OperationType type) {
        return type == OperationType.DISBURSEMENT ? "/disbursements" : "/collections";
    }

    private ProviderStatus call(ProviderCode providerCode, Call call) {
        try {
            ResponseEntity<JsonNode> response = call.execute();
            return parse(response.getBody());

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new ProviderUnavailableException(
                        "%s a repondu %s : aucune conclusion possible"
                                .formatted(providerCode, e.getStatusCode()), e);
            }
            if (e.getStatusCode().value() == 404) {
                // L'operateur ne connait pas cette reference. Tentant d'y voir un echec,
                // mais on ne peut pas : apres un appel initial expire, il est impossible
                // de distinguer "la demande n'est jamais arrivee" de "elle n'est pas
                // encore indexee". On reste donc en attente et la relance tranchera, quitte
                // a epuiser le budget et a demander un arbitrage.
                log.debug("{} ne connait pas encore la reference : traite comme non conclusif",
                        providerCode);
                return ProviderStatus.pending(null);
            }
            return rejection(e);

        } catch (ResourceAccessException e) {
            // Delai depasse ou connexion impossible. L'operation a peut-etre abouti.
            throw new ProviderUnavailableException(
                    "%s injoignable : aucune conclusion possible".formatted(providerCode), e);
        }
    }

    private ProviderStatus parse(JsonNode body) {
        if (body == null || !body.hasNonNull("status")) {
            // Une reponse illisible n'est pas une reponse. Mieux vaut retenter que
            // d'inventer un statut.
            throw new ProviderUnavailableException("Reponse operateur illisible", null);
        }
        String status = body.get("status").asText();
        String providerRef = body.hasNonNull("providerRef") ? body.get("providerRef").asText() : null;
        String currency = body.hasNonNull("currency") ? body.get("currency").asText() : null;
        Money fee = body.hasNonNull("fee") && currency != null
                ? Money.parse(body.get("fee").asText(), currency)
                : null;

        return switch (status) {
            case "SUCCEEDED" -> new ProviderStatus(Outcome.SUCCEEDED, providerRef, fee, null, null);
            case "FAILED" -> new ProviderStatus(Outcome.FAILED, providerRef, null,
                    text(body, "errorCode"), text(body, "errorMessage"));
            default -> ProviderStatus.pending(providerRef);
        };
    }

    private ProviderStatus rejection(HttpStatusCodeException e) {
        log.info("Demande refusee par l'operateur ({})", e.getStatusCode());
        return new ProviderStatus(Outcome.FAILED, null, null,
                "PROVIDER_REJECTED_" + e.getStatusCode().value(),
                "L'operateur a refuse la demande");
    }

    private String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    @FunctionalInterface
    private interface Call {
        ResponseEntity<JsonNode> execute();
    }
}
