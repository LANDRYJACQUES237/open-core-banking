package com.ocb.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Deux taches planifiees sont vitales ici : le relais d'outbox, et la relance de statut.
 *
 * <p>Sans {@code @EnableScheduling}, les operations sans reponse ne seraient jamais
 * relancees et resteraient en attente indefiniment — panne parfaitement silencieuse,
 * puisque tout le reste continuerait de fonctionner.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ProviderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProviderServiceApplication.class, args);
    }
}
