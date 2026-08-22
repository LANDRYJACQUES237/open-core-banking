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
                            @Value("${ocb.ledger.base-url}") String baseUrl,
                            @Value("${ocb.ledger.connect-timeout:PT2S}") Duration connectTimeout,
                            @Value("${ocb.ledger.read-timeout:PT10S}") Duration readTimeout) {
        this.baseUrl = baseUrl;
        this.restTemplate = builder
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .build();
    }

    @Override
    public String post(EntryRequest request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", request.idempotencyKey());

        Map<String, Object> body = new LinkedHashMap<>();
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
