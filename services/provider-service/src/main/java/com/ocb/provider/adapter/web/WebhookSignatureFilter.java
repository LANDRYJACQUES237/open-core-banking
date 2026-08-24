package com.ocb.provider.adapter.web;

import com.ocb.provider.application.RejectedCallbackRecorder;
import com.ocb.provider.config.WebhookProperties;
import com.ocb.provider.domain.ProviderErrors;
import com.ocb.provider.domain.WebhookSignature;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Authentifie les rappels entrants avant qu'ils n'atteignent quoi que ce soit d'autre.
 *
 * <p><b>Pourquoi un filtre et non une verification dans le controleur.</b> Un controleur
 * recoit un objet deja construit par Jackson : au moment ou il pourrait verifier la
 * signature, la charge utile non authentifiee a deja ete analysee. Analyser du JSON venu
 * d'Internet avant de savoir qui l'envoie, c'est offrir la surface d'attaque du parseur a
 * n'importe qui. Ici, une requete mal signee n'atteint jamais Jackson.
 *
 * <p>Le corps est lu une fois, conserve, et rejoue ensuite pour le controleur — la
 * signature portant sur les octets bruts, une representation reserialisee ne conviendrait
 * pas.
 *
 * <p>C'est la seule surface publique de la plateforme. Les tentatives refusees sont
 * journalisees : elles ne sont pas des incidents techniques mais un signal de securite.
 */
public class WebhookSignatureFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(WebhookSignatureFilter.class);

    static final String RAW_BODY_ATTRIBUTE = "ocb.webhook.rawBody";

    private static final String SIGNATURE_HEADER = "X-OCB-Signature";
    private static final String TIMESTAMP_HEADER = "X-OCB-Timestamp";

    private final WebhookProperties properties;
    private final RejectedCallbackRecorder rejectedRecorder;

    public WebhookSignatureFilter(WebhookProperties properties,
                                  RejectedCallbackRecorder rejectedRecorder) {
        this.properties = properties;
        this.rejectedRecorder = rejectedRecorder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        byte[] rawBytes = request.getInputStream().readAllBytes();
        String rawBody = new String(rawBytes, StandardCharsets.UTF_8);

        String providerCode = providerCodeFrom(request.getRequestURI());
        String secret = providerCode == null ? null : properties.secretFor(providerCode);

        if (secret == null || secret.isBlank()) {
            // Aucun secret configure pour cet operateur. On refuse plutot que de laisser
            // passer : un secret vide validerait toute signature calculee avec une chaine
            // vide, ce qui reviendrait a n'avoir aucune protection tout en en affichant une.
            log.error("Aucun secret configure pour l'operateur {} : rappel refuse", providerCode);
            reject(response, ProviderErrors.UNKNOWN_PROVIDER,
                    "Operateur inconnu ou non configure");
            return;
        }

        WebhookSignature.Verdict verdict = WebhookSignature.verify(
                request.getHeader(SIGNATURE_HEADER),
                request.getHeader(TIMESTAMP_HEADER),
                rawBody,
                secret,
                properties.getReplayWindow(),
                Instant.now());

        if (!verdict.isValid()) {
            log.warn("Rappel refuse pour {} : {}", providerCode, verdict);
            rejectedRecorder.record(providerCode, verdict.name(),
                    request.getHeader(SIGNATURE_HEADER), rawBody);
            reject(response,
                    verdict == WebhookSignature.Verdict.EXPIRED
                            ? ProviderErrors.SIGNATURE_EXPIRED
                            : ProviderErrors.INVALID_SIGNATURE,
                    "Signature absente, invalide ou expiree");
            return;
        }

        request.setAttribute(RAW_BODY_ATTRIBUTE, rawBody);
        chain.doFilter(new CachedBodyHttpServletRequest(request, rawBytes), response);
    }

    private String providerCodeFrom(String uri) {
        String[] segments = uri.split("/");
        return segments.length >= 3 ? segments[2] : null;
    }

    /**
     * Reponse volontairement laconique.
     *
     * <p>Distinguer "signature invalide" de "horodatage expire" dans le corps aiderait
     * autant l'operateur legitime qu'un attaquant qui cherche a comprendre le schema. Le
     * detail va dans les journaux et l'audit, pas sur le fil.
     */
    private void reject(HttpServletResponse response, String code, String detail) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write("""
                {"type":"https://ocb.dev/problems/%s","title":"Rappel non authentifie","status":401,\
                "detail":"%s","code":"%s"}"""
                .formatted(code.toLowerCase().replace('_', '-'), detail, code));
    }
}
