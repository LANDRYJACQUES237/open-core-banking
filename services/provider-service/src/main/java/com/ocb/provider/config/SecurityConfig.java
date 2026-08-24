package com.ocb.provider.config;

import com.ocb.platform.security.OcbScopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Regles d'acces de l'adaptateur operateur.
 *
 * <p><b>Le point qui demande une explication : les webhooks ne sont pas proteges par un
 * jeton, et c'est correct.</b>
 *
 * <p>Un operateur Mobile Money ne dispose d'aucun jeton emis par notre fournisseur
 * d'identite, et n'en disposera jamais : il ne fait pas partie de notre domaine de
 * confiance. Exiger un jeton la reviendrait soit a lui distribuer un identifiant client —
 * donc a lui accorder une identite dans notre systeme pour un usage qui n'en a pas besoin
 * — soit, plus probablement, a ouvrir le point de terminaison faute de mieux.
 *
 * <p>L'authentification y est assuree autrement, par une <b>signature HMAC verifiee sur le
 * corps brut</b>, avec horodatage et fenetre de rejeu. Le controle a lieu dans un filtre
 * qui s'execute avant celui-ci ({@code WebhookSignatureFilter}) : une requete mal signee
 * n'atteint jamais la chaine de securite Spring, ni Jackson, ni le controleur.
 *
 * <p>{@code permitAll} ici signifie donc « authentifie autrement », pas « ouvert ». La
 * distinction est invisible dans la configuration, d'ou ce commentaire — et d'ou le test
 * qui verifie explicitement que ces deux chemins se comportent differemment.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain providerSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()

                        // Authentifie par signature HMAC, en amont de cette chaine.
                        .requestMatchers("/webhooks/**").permitAll()

                        // Le diagnostic, lui, reste derriere un jeton : l'etat d'une
                        // operation revele des montants et des references de transaction.
                        .requestMatchers(HttpMethod.GET, "/v1/operations/**")
                        .hasAuthority(OcbScopes.authority(OcbScopes.PROVIDER_READ))

                        .anyRequest().denyAll())

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
