package com.ocb.provider.adapter.web;

import com.ocb.platform.web.CorrelationIdFilter;
import com.ocb.provider.api.WebhooksApi;
import com.ocb.provider.api.model.CallbackAck;
import com.ocb.provider.api.model.ProviderCallback;
import com.ocb.provider.application.CallbackService;
import com.ocb.provider.domain.ProviderCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WebhooksController implements WebhooksApi {

    private final CallbackService callbackService;
    private final HttpServletRequest request;

    public WebhooksController(CallbackService callbackService, HttpServletRequest request) {
        this.callbackService = callbackService;
        this.request = request;
    }

    /**
     * Recoit un rappel deja authentifie.
     *
     * <p>La signature a ete verifiee par {@link WebhookSignatureFilter} avant que Jackson
     * ne touche a la charge utile : ce qui arrive ici vient bien de l'operateur.
     *
     * <p><b>La reponse est un succes meme pour un doublon ou un rappel tardif.</b>
     * Repondre en erreur declencherait des retentatives sur une operation deja close,
     * c'est-a-dire une tempete d'appels sans objet — et un operateur qui finirait par
     * considerer notre point de terminaison comme defaillant.
     */
    @Override
    public ResponseEntity<CallbackAck> receiveCallback(String providerCode,
                                                       String signature,
                                                       String timestamp,
                                                       ProviderCallback callback) {

        // Le corps brut, conserve par le filtre. La representation reserialisee par
        // Jackson ne conviendrait pas : c'est sur ces octets exacts que la signature a
        // ete calculee, et c'est eux qu'il faut archiver pour un eventuel litige.
        String rawBody = (String) request.getAttribute(WebhookSignatureFilter.RAW_BODY_ATTRIBUTE);

        CallbackService.Result result = callbackService.process(
                ProviderCode.valueOf(providerCode),
                callback.getEventId(),
                callback.getExternalRef(),
                callback.getProviderRef(),
                callback.getStatus().getValue(),
                callback.getFee(),
                callback.getCurrency(),
                callback.getErrorCode(),
                callback.getErrorMessage(),
                rawBody,
                signature,
                CorrelationIdFilter.current());

        CallbackAck ack = new CallbackAck(result.received());
        ack.setDuplicate(result.duplicate());
        return ResponseEntity.ok(ack);
    }
}
