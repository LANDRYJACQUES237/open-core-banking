package com.ocb.payment.adapter.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.ocb.payment.domain.PaymentErrors;
import com.ocb.payment.domain.port.LedgerPort;
import com.ocb.platform.domain.error.InvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client du grand livre.
 *
 * <p>La distinction essentielle de cette classe tient en deux cas d'erreur qu'il ne faut
 * jamais confondre :
 *
 * <ul>
 *   <li><b>un refus</b> (4xx) est une reponse : le grand livre a examine la demande et l'a
 *       rejetee. Rien n'a ete ecrit, on peut conclure ;
 *   <li><b>une absence de reponse</b> (timeout, 5xx, connexion refusee) n'est pas une
 *       reponse. L'ecriture a peut-etre eu lieu. Conclure a l'echec ici conduirait a
 *       rejouer un mouvement d'argent ou a le declarer perdu alors qu'il est enregistre.
 * </ul>
 *
 * <p>Le second cas remonte en {@link LedgerPort.LedgerUnavailableException}, que l'appelant
 * traduit en revue manuelle plutot qu'en echec.
 */
@Component
public class LedgerRestClient implements LedgerPort {

    private static final Logger log = LoggerFactory.getLogger(LedgerRestClient.class);

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public LedgerRestClient(RestTemplateBuilder builder,
                            org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager
                                    authorizedClientManager,
                            @Value("${ocb.ledger.base-url}") String baseUrl,
                            @Value("${ocb.ledger.registration-id:ledger}") String registrationId,
                            @Value("${ocb.ledger.connect-timeout:PT2S}") Duration connectTimeout,
                            @Value("${ocb.ledger.read-timeout:PT10S}") Duration readTimeout) {
        this.baseUrl = baseUrl;
        this.restTemplate = builder
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                // Le jeton de service est attache par un intercepteur plutot qu'a chaque
                // appel : un en-tete pose a la main finit toujours par etre oublie sur un
                // nouveau point d'appel, et l'oubli se manifeste par un 401 que le client
                // traduirait en refus du grand livre.
                .additionalInterceptors(new ClientCredentialsInterceptor(
                        authorizedClientManager, registrationId, "payment-service"))
                .build();
    }

    @Override
    public String post(EntryRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", request.idempotencyKey());

        Map<String, Object> body = new LinkedHashMap<>();
        if (request.entryRef() != null) {
            body.put("entryRef", request.entryRef());
        }
        body.put("transactionRef", request.transactionRef());
        body.put("description", request.description());
        body.put("lines", request.lines().stream().map(line -> Map.of(
                "accountNumber", line.accountNumber(),
                "direction", line.direction(),
                // Chaine et non nombre : un nombre JSON serait parse en double par le
                // recepteur, ce qui detruirait la precision du montant.
                "amount", line.amount().toPlainString(),
                "currency", line.amount().currencyCode())).toList());

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + "/v1/journal-entries", HttpMethod.POST,
                    new HttpEntity<>(body, headers), JsonNode.class);

            JsonNode payload = response.getBody();
            if (payload == null || !payload.has("entryRef")) {
                throw new IllegalStateException("Reponse du grand livre sans entryRef");
            }
            // 201 a la creation, 200 sur rejeu : les deux sont un succes. C'est ce qui rend
            // sur le rejeu d'un message Kafka apres un arret entre l'appel et le commit.
            return payload.get("entryRef").asText();

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new LedgerUnavailableException(
                        "Le grand livre a repondu %s : impossible de conclure".formatted(e.getStatusCode()), e);
            }
            throw refusal(e);

        } catch (ResourceAccessException e) {
            // Timeout ou connexion impossible. L'ecriture a peut-etre eu lieu.
            throw new LedgerUnavailableException("Grand livre injoignable : impossible de conclure", e);
        }
    }

    @Override
    public com.ocb.platform.domain.money.Money balanceOf(String accountNumber) {
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + "/v1/accounts/{account}/balance", HttpMethod.GET,
                    new HttpEntity<>(new HttpHeaders()), JsonNode.class, accountNumber);

            JsonNode payload = response.getBody();
            if (payload == null || !payload.has("balance") || !payload.has("currency")) {
                throw new IllegalStateException("Reponse de solde incomplete du grand livre");
            }
            // Le solde est lu comme une chaine, jamais comme un nombre JSON : asDouble()
            // ou asDecimal() sur un noeud numerique passerait par un double des que la
            // valeur depasse la precision exacte, et un solde faux ici autoriserait un
            // decaissement qui ne devrait pas passer.
            return com.ocb.platform.domain.money.Money.parse(
                    payload.get("balance").asText(), payload.get("currency").asText());

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new LedgerUnavailableException(
                        "Le grand livre a repondu %s : solde inconnu".formatted(e.getStatusCode()), e);
            }
            throw refusal(e);

        } catch (ResourceAccessException e) {
            // Ne surtout pas retomber sur un solde nul : ce serait refuser des demandes
            // parfaitement financables et faire passer une panne pour un manque d'argent.
            throw new LedgerUnavailableException("Grand livre injoignable : solde inconnu", e);
        }
    }

    @Override
    public String reverse(ReversalRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", request.idempotencyKey());

        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + "/v1/journal-entries/{entryRef}/reversal", HttpMethod.POST,
                    new HttpEntity<>(Map.of("reason", request.reason()), headers),
                    JsonNode.class, request.originalEntryRef());

            JsonNode payload = response.getBody();
            if (payload == null || !payload.has("entryRef")) {
                throw new IllegalStateException("Reponse de contre-passation sans entryRef");
            }
            // 201 a la creation, 200 sur rejeu de la meme cle : les deux disent que la
            // compensation existe, ce qui est tout ce que l'appelant a besoin de savoir.
            return payload.get("entryRef").asText();

        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().is5xxServerError()) {
                throw new LedgerUnavailableException(
                        "Le grand livre a repondu %s : compensation incertaine"
                                .formatted(e.getStatusCode()), e);
            }
            throw refusal(e);

        } catch (ResourceAccessException e) {
            throw new LedgerUnavailableException(
                    "Grand livre injoignable : compensation incertaine", e);
        }
    }

    private RuntimeException refusal(HttpStatusCodeException e) {
        String code = PaymentErrors.LEDGER_REJECTED;
        String detail = e.getResponseBodyAsString();
        log.warn("Ecriture refusee par le grand livre ({}) : {}", e.getStatusCode(), detail);
        return new InvariantViolationException(code,
                "Le grand livre a refuse l'ecriture (%s)".formatted(e.getStatusCode()));
    }

    /** Expose la construction des lignes pour les tests de mapping. */
    static List<Line> linesOf(EntryRequest request) {
        return request.lines();
    }
}
