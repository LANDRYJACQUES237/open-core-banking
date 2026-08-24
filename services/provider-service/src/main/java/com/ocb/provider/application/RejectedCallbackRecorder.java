package com.ocb.provider.application;

import com.ocb.provider.domain.port.AuditStore;
import com.ocb.platform.web.CorrelationIdFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Conserve la trace des rappels refuses.
 *
 * <p>Un rappel mal signe n'est pas un incident technique a ignorer : c'est soit un
 * operateur mal configure, soit quelqu'un qui essaie. Les deux meritent d'etre visibles,
 * et le second surtout.
 *
 * <p>Le corps est tronque : il vient d'un appelant non authentifie et rien ne garantit sa
 * taille. Conserver l'integralite offrirait un moyen simple de remplir la base.
 */
@Service
public class RejectedCallbackRecorder {

    private static final int MAX_STORED_BODY = 2000;

    private final AuditStore audit;

    public RejectedCallbackRecorder(AuditStore audit) {
        this.audit = audit;
    }

    @Transactional
    public void record(String providerCode, String verdict, String signature, String rawBody) {
        audit.append("WEBHOOK_REJECTED", "ProviderCallback", providerCode,
                CorrelationIdFilter.current(),
                Map.of("verdict", verdict,
                        "signaturePresent", signature != null,
                        "bodyExcerpt", truncate(rawBody)));
    }

    private String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MAX_STORED_BODY ? body : body.substring(0, MAX_STORED_BODY) + "...";
    }
}
