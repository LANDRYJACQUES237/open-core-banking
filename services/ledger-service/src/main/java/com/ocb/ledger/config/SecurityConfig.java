package com.ocb.ledger.config;

import com.ocb.platform.security.OcbScopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Regles d'acces au grand livre.
 *
 * <p><b>Le point qui compte : {@code ledger:post} n'est accorde qu'au compte de service du
 * moteur de paiement.</b> Aucun client externe ne peut ecrire dans le grand livre, en
 * aucune circonstance. Un marchand qui pourrait passer ses propres ecritures contournerait
 * la machine a etats, l'idempotence et le calcul des frais — et pourrait se crediter
 * lui-meme.
 *
 * <p>La separation entre lecture et ecriture est portee par deux portees distinctes plutot
 * que par un role unique : une console de supervision a besoin de lire les soldes, jamais
 * de les modifier.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain ledgerSecurity(HttpSecurity http) throws Exception {
        http
                // API sans etat : aucun cookie de session, donc aucune surface CSRF.
                // Desactiver la protection CSRF sans etre sans etat serait une faute ;
                // les deux vont ensemble.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Les sondes de Kubernetes n'ont pas de jeton. Elles ne revelent
                        // rien : show-details est a "never".
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Ecriture comptable : le moteur de paiement, et personne d'autre.
                        .requestMatchers(HttpMethod.POST, "/v1/journal-entries/**")
                        .hasAuthority(OcbScopes.authority(OcbScopes.LEDGER_POST))
                        .requestMatchers(HttpMethod.POST, "/v1/accounts/**")
                        .hasAuthority(OcbScopes.authority(OcbScopes.LEDGER_POST))

                        // Lecture : supervision, rapprochement, console.
                        .requestMatchers(HttpMethod.GET, "/v1/**")
                        .hasAuthority(OcbScopes.authority(OcbScopes.LEDGER_READ))

                        // Tout le reste est refuse. Une regle par defaut permissive
                        // transformerait chaque nouveau point de terminaison en ouverture
                        // involontaire.
                        .anyRequest().denyAll())

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
