package com.ocb.platform.web;

import com.ocb.platform.domain.error.ConflictException;
import com.ocb.platform.domain.error.DomainException;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.error.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Traduit les exceptions en reponses RFC 7807, une seule fois, pour tous les services.
 *
 * <p>Deux regles de fond :
 *
 * <ul>
 *   <li><b>Le domaine ne connait pas HTTP.</b> La correspondance exception -&gt; statut
 *       vit ici et nulle part ailleurs, ce qui permet de reutiliser le meme domaine
 *       derriere un consommateur Kafka en Phase 2.
 *   <li><b>Une erreur inattendue ne divulgue rien.</b> Le message d'une exception non
 *       prevue peut contenir un fragment de requete SQL, un identifiant technique ou une
 *       valeur metier. Il est journalise, jamais renvoye. Seul le correlationId permet
 *       de faire le lien entre la reponse et la trace serveur.
 * </ul>
 */
@RestControllerAdvice
public class ProblemDetailsExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailsExceptionHandler.class);
    private static final String TYPE_BASE = "https://ocb.dev/problems/";

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Ressource introuvable", ex);
    }

    @ExceptionHandler(ConflictException.class)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "Conflit avec l'etat courant", ex);
    }

    @ExceptionHandler(InvariantViolationException.class)
    public ProblemDetail handleInvariant(InvariantViolationException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Regle metier violee", ex);
    }

    /** Filet pour toute sous-classe future non listee ci-dessus. */
    @ExceptionHandler(DomainException.class)
    public ProblemDetail handleDomain(DomainException ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Requete refusee", ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(fe ->
                errors.add(Map.of("field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalide" : fe.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(ge ->
                errors.add(Map.of("field", ge.getObjectName(),
                        "message", ge.getDefaultMessage() == null ? "invalide" : ge.getDefaultMessage())));

        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "Requete invalide",
                "VALIDATION_FAILED", "La requete ne respecte pas le contrat.");
        pd.setProperty("errors", errors);
        return pd;
    }

    /**
     * Levee quand une contrainte porte sur un parametre de methode (chemin, query, en-tete)
     * plutot que sur le corps. Les interfaces generees depuis le contrat OpenAPI en produisent
     * beaucoup, via les @Pattern et @Size poses sur les @PathVariable.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ProblemDetail handleParameterValidation(HandlerMethodValidationException ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getAllValidationResults()) {
            collect(result.getMethodParameter().getParameterName(), result, errors);
        }

        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "Requete invalide",
                "VALIDATION_FAILED", "Un parametre de la requete ne respecte pas le contrat.");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        // Le message d'origine expose la structure interne des DTO : on ne le propage pas.
        log.debug("Corps de requete illisible", ex);
        return base(HttpStatus.BAD_REQUEST, "Requete invalide",
                "MALFORMED_BODY", "Le corps de la requete n'a pas pu etre lu.");
    }

    /**
     * Filet final.
     *
     * <p>Spring MVC leve ses propres exceptions pour des situations parfaitement normales :
     * en-tete obligatoire absent, chemin inconnu, methode non autorisee, type de contenu
     * refuse. Elles portent deja leur statut via {@link ErrorResponse}. Les faire tomber
     * dans le cas "erreur inattendue" les transformerait en 500, ce qui mentirait a
     * l'appelant sur la nature du probleme et masquerait les vraies pannes serveur au
     * milieu du bruit.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            HttpStatusCode status = errorResponse.getStatusCode();
            // Le detail d'origine decrit la requete, pas l'etat interne du serveur :
            // il est sur pour un 4xx, il ne le serait pas pour un 5xx.
            String detail = status.is4xxClientError()
                    ? errorResponse.getBody().getDetail()
                    : "Une erreur interne est survenue. Communiquez le correlationId au support.";
            if (status.is5xxServerError()) {
                log.error("Erreur serveur [correlationId={}]", CorrelationIdFilter.current(), ex);
            }
            return base(status, titleFor(status), codeFor(ex), detail);
        }

        log.error("Erreur inattendue [correlationId={}]", CorrelationIdFilter.current(), ex);
        return base(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur interne",
                "INTERNAL_ERROR",
                "Une erreur interne est survenue. Communiquez le correlationId au support.");
    }

    private static String titleFor(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Erreur";
    }

    /** {@code MissingRequestHeaderException} devient {@code MISSING_REQUEST_HEADER}. */
    private static String codeFor(Exception ex) {
        String name = ex.getClass().getSimpleName().replaceAll("Exception$", "");
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private ProblemDetail problem(HttpStatus status, String title, DomainException ex) {
        return base(status, title, ex.code(), ex.getMessage());
    }

    private ProblemDetail base(HttpStatusCode status, String title, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        pd.setProperty("code", code);
        String correlationId = CorrelationIdFilter.current();
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
        return pd;
    }

    private static void collect(String name,
                                ParameterValidationResult result,
                                List<Map<String, String>> sink) {
        result.getResolvableErrors().forEach(err ->
                sink.add(Map.of(
                        "field", name == null || name.isBlank() ? "?" : name,
                        "message", err.getDefaultMessage() == null ? "invalide" : err.getDefaultMessage())));
    }
}
