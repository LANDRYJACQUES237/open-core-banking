package com.ocb.platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "ocb.security")
public class ResourceServerProperties {

    /**
     * Audience attendue dans les jetons.
     *
     * <p>Verifier l'audience n'est pas une formalite. Sans elle, un jeton legitimement
     * emis pour un autre service — une console d'administration, un outil interne — serait
     * accepte ici avec toutes ses portees. Un jeton doit dire non seulement qui le porte,
     * mais pour quel destinataire il a ete emis.
     */
    private List<String> audiences = List.of();

    /**
     * Tolerance d'horloge lors de la verification d'expiration.
     *
     * <p>Quelques secondes suffisent : au-dela, on prolonge la duree de vie effective des
     * jetons, donc la fenetre pendant laquelle un jeton vole reste utilisable.
     */
    private java.time.Duration clockSkew = java.time.Duration.ofSeconds(30);

    public List<String> getAudiences() {
        return audiences;
    }

    public void setAudiences(List<String> audiences) {
        this.audiences = audiences;
    }

    public java.time.Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(java.time.Duration clockSkew) {
        this.clockSkew = clockSkew;
    }
}
