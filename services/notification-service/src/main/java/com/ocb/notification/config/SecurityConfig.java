package com.ocb.notification.config;

import com.ocb.platform.security.OcbScopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Qui peut consulter les notifications emises.
 *
 * <p>Ce service n'expose aucune ecriture : la seule surface HTTP est une lecture de
 * diagnostic. Elle n'en est pas moins sensible — la liste des messages envoyes a un
 * portefeuille renseigne sur son activite.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()

                        .requestMatchers(HttpMethod.GET, "/v1/notifications/**")
                        .hasAuthority(OcbScopes.authority(OcbScopes.NOTIFICATION_READ))

                        // Tout le reste est refuse par defaut : un point d'entree ajoute
                        // plus tard reste ferme tant que personne ne l'a ouvert.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {
                }));

        return http.build();
    }
}
