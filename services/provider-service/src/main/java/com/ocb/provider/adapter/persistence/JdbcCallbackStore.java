package com.ocb.provider.adapter.persistence;

import com.ocb.provider.domain.ProviderCode;
import com.ocb.provider.domain.port.CallbackStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcCallbackStore implements CallbackStore {

    private final JdbcClient jdbc;

    public JdbcCallbackStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * L'insertion est la deduplication.
     *
     * <p>Pas de lecture prealable : entre le SELECT et l'INSERT, un second exemplaire du
     * meme rappel pourrait etre traite. La contrainte d'unicite fait le travail de maniere
     * atomique, et {@code ON CONFLICT DO NOTHING} transforme le doublon en zero ligne
     * inseree plutot qu'en erreur qui avorterait la transaction.
     */
    @Override
    public boolean record(ProviderCode providerCode, String providerEventId, String externalRef,
                          UUID transactionId, String signature, boolean signatureValid,
                          String rawPayload) {
        return jdbc.sql("""
                        INSERT INTO provider.provider_callback
                            (id, provider_code, provider_event_id, external_ref, transaction_id,
                             signature, signature_valid, raw_payload)
                        VALUES
                            (:id, :providerCode, :eventId, :externalRef, :transactionId,
                             :signature, :signatureValid, :rawPayload)
                        ON CONFLICT ON CONSTRAINT ux_callback_event DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("providerCode", providerCode.name())
                .param("eventId", providerEventId)
                .param("externalRef", externalRef)
                .param("transactionId", transactionId)
                .param("signature", signature)
                .param("signatureValid", signatureValid)
                .param("rawPayload", rawPayload)
                .update() == 1;
    }

    @Override
    public void markProcessed(ProviderCode providerCode, String providerEventId) {
        jdbc.sql("""
                        UPDATE provider.provider_callback SET processed = true
                         WHERE provider_code = :code AND provider_event_id = :eventId
                        """)
                .param("code", providerCode.name())
                .param("eventId", providerEventId)
                .update();
    }
}
