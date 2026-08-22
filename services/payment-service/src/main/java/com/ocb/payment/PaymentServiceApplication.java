package com.ocb.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Le relais d'outbox est une tache planifiee : sans {@code @EnableScheduling}, les
 * evenements seraient correctement ecrits mais jamais publies — panne silencieuse par
 * excellence, puisque toutes les ecritures metier continueraient de fonctionner.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class PaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
