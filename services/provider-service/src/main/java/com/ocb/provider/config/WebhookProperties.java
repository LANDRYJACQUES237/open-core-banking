package com.ocb.provider.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secrets de signature des webhooks, un par operateur.
 *
 * <p>Jamais de valeur par defaut : un secret code en dur serait publie avec le depot, et
 * un secret vide accepterait n'importe quelle signature calculee avec une chaine vide.
 * L'absence de configuration doit faire echouer la verification, pas la contourner.
 */
@ConfigurationProperties(prefix = "ocb.provider.webhook")
public class WebhookProperties {

    /** Secret partage par operateur, renseigne par variable d'environnement. */
    private Map<String, String> secrets = new LinkedHashMap<>();

    /**
     * Fenetre de rejeu.
     *
     * <p>Elle borne la valeur d'une signature capturee : au-dela, la requete est refusee
     * meme si la signature est correcte. Quelques minutes suffisent pour absorber une
     * derive d'horloge entre deux systemes qu'on ne controle pas.
     */
    private Duration replayWindow = Duration.ofMinutes(5);

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }

    public Duration getReplayWindow() {
        return replayWindow;
    }

    public void setReplayWindow(Duration replayWindow) {
        this.replayWindow = replayWindow;
    }

    public String secretFor(String providerCode) {
        return secrets.get(providerCode);
    }
}
