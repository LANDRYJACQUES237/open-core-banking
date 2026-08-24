package com.ocb.provider.adapter.operator;

import com.ocb.provider.domain.ProviderCode;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adresses et delais des API operateur.
 *
 * <p>Un seul point de configuration pour tous les operateurs : leur URL est la seule chose
 * qui distingue le simulateur du vrai MTN. C'est ce qui permettra, quand les acces reels
 * arriveront, de basculer sans toucher a une ligne de code de ce service.
 */
@ConfigurationProperties(prefix = "ocb.provider.operators")
public class OperatorEndpoints {

    private Map<String, String> baseUrls = new LinkedHashMap<>();

    /** Ne pas pouvoir ouvrir une connexion rapidement signale un operateur injoignable. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /**
     * Un operateur lent mais vivant merite plus de patience qu'un operateur injoignable,
     * d'ou un delai distinct. Il reste borne : au-dela, mieux vaut relacher le fil et
     * relancer plus tard que d'immobiliser un consommateur.
     */
    private Duration readTimeout = Duration.ofSeconds(15);

    public String baseUrlFor(ProviderCode code) {
        String url = baseUrls.get(code.name());
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(
                    "Aucune adresse configuree pour l'operateur " + code);
        }
        return url;
    }

    public Map<String, String> getBaseUrls() {
        return baseUrls;
    }

    public void setBaseUrls(Map<String, String> baseUrls) {
        this.baseUrls = baseUrls;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
