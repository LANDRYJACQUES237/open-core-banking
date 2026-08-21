package com.ocb.ledger.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Active les taches d'entretien.
 *
 * <p>Desactivable par configuration, et desactivee dans les tests : une tache planifiee
 * qui consolide des instantanes en arriere-plan pendant qu'un test verifie un solde
 * transforme un echec deterministe en echec intermittent. Les tests appellent les memes
 * methodes explicitement, ce qui rend le moment de leur execution observable.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "ledger.maintenance.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
