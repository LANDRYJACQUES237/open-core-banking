package com.ocb.payment.domain.port;

import java.util.UUID;

/**
 * Idempotence de la couche HTTP.
 *
 * <p>Quatre issues possibles pour une cle, et les confondre a des consequences reelles :
 *
 * <ul>
 *   <li>{@code FRESH} — cle inconnue, on traite ;
 *   <li>{@code REPLAY} — meme cle, meme contenu : on rejoue la reponse memoisee sans rien
 *       redeclencher ;
 *   <li>{@code MISMATCH} — meme cle, contenu different : on refuse. Renvoyer l'ancienne
 *       reponse ferait croire a l'appelant que sa nouvelle demande a ete traitee alors
 *       qu'elle a ete ignoree, ce qui est la maniere la plus discrete de perdre un
 *       paiement ;
 *   <li>{@code IN_PROGRESS} — une requete portant cette cle est encore en vol. On ne peut
 *       ni traiter ni repondre, on demande a l'appelant de reessayer.
 * </ul>
 */
public interface IdempotencyStore {

    Claim claim(String scope, String key, String requestHash);

    void complete(String scope, String key, int httpStatus, String responseBody, UUID resourceId);

    record Claim(Outcome outcome, Integer httpStatus, String responseBody, UUID resourceId) {

        public static Claim fresh() {
            return new Claim(Outcome.FRESH, null, null, null);
        }

        public boolean isFresh() {
            return outcome == Outcome.FRESH;
        }

        public enum Outcome {
            FRESH, REPLAY, MISMATCH, IN_PROGRESS
        }
    }
}
