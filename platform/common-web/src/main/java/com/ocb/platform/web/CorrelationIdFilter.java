package com.ocb.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Attache un identifiant de correlation a chaque requete, le place dans le MDC et le
 * renvoie dans la reponse.
 *
 * <p>C'est la brique qui rendra une transaction suivable de bout en bout quand les
 * quatre services seront en place : le meme identifiant traversera l'appel REST initial,
 * les en-tetes Kafka et la notification finale. Le mettre en place des le premier service
 * coute quelques lignes ; le rajouter apres coup oblige a reprendre chaque point de log.
 *
 * <p>L'identifiant fourni par l'appelant est accepte, mais valide : sans controle de format,
 * un client peut injecter des sauts de ligne dans les logs et fabriquer de fausses entrees
 * (log forging). Une valeur non conforme est remplacee, jamais recopiee telle quelle.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String correlationId = (incoming != null && SAFE.matcher(incoming).matches())
                ? incoming
                : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Indispensable : les threads de servlet sont mutualises. Sans nettoyage,
            // la requete suivante heriterait du correlationId de la precedente.
            MDC.remove(MDC_KEY);
        }
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }
}
