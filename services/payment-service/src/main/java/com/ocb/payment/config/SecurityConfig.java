package com.ocb.payment.config;

import com.ocb.platform.security.OcbScopes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    /**
     * Regles d'acces au moteur de paiement.
     *
     * <p>Deux portees separees pour deux populations : un marchand initie des paiements,
     * une console de supervision les consulte. Confondre les deux donnerait a tout lecteur
     * le pouvoir de declencher un prelevement.
     */
    @Bean
    public SecurityFilterChain paymentSecurity(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()

                        .requestMatchers(HttpMethod.POST, "/v1/collections")
                        .hasAuthority(OcbScopes.authority(OcbScopes.PAYMENT_INITIATE))

                        // Meme portee que l'encaissement : dans les deux cas le marchand
                        // demande un mouvement d'argent sur un portefeuille qu'il gere.
                        // Ce qui distingue le decaissement n'est pas qui a le droit de le
                        // demander, mais ce qu'il engage — et cela releve du solde, pas de
                        // l'autorisation.
                        .requestMatchers(HttpMethod.POST, "/v1/disbursements")
                        .hasAuthority(OcbScopes.authority(OcbScopes.PAYMENT_INITIATE))

                        .requestMatchers(HttpMethod.GET, "/v1/transactions/**")
                        .hasAuthority(OcbScopes.authority(OcbScopes.PAYMENT_READ))

                        .anyRequest().denyAll())

                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    /**
     * Gestionnaire de jetons pour les appels sortants vers le grand livre.
     *
     * <p>Flux {@code client_credentials} : ce service s'authentifie en tant que lui-meme,
     * pas au nom de l'appelant. C'est voulu — l'ecriture comptable est une decision du
     * moteur de paiement, prise apres validation, et non une action deleguee par le
     * marchand. Propager le jeton de l'appelant reviendrait a lui donner indirectement
     * {@code ledger:post}.
     *
     * <p>La variante {@code AuthorizedClientService} est celle qui convient hors contexte
     * de requete : les appels au grand livre partent aussi depuis un consommateur Kafka,
     * ou aucune session ni requete HTTP n'existe.
     */
    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientService clients) {

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clients);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }
}
