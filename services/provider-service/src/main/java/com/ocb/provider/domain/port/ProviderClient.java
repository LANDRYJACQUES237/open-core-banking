package com.ocb.provider.domain.port;

import com.ocb.platform.domain.money.Money;
import com.ocb.provider.domain.ProviderCode;

/**
 * Dialogue avec un operateur Mobile Money.
 *
 * <p><b>Le point de conception le plus important de ce service tient dans ce qui n'est pas
 * ici : il n'existe aucun statut "inconnu".</b>
 *
 * <p>Une absence de reponse n'est pas une valeur de retour, c'est une
 * {@link ProviderUnavailableException}. Ce choix est deliberatement contraignant. Si
 * l'absence de reponse etait un statut parmi d'autres, un appelant pourrait l'affecter a
 * une variable, la comparer, l'oublier dans un {@code switch} — et finir par la traiter
 * comme un echec, ce qui est la faute la plus couteuse d'un systeme de paiement. Une
 * exception, elle, doit etre attrapee explicitement : le code qui l'ignore ne compile pas
 * en silence, il remonte.
 *
 * <p>Les trois valeurs possibles de {@link Outcome} sont donc toutes des <b>reponses</b>
 * de l'operateur, y compris {@code PENDING} qui signifie "je l'ai, je n'ai pas fini".
 */
public interface ProviderClient {

    /**
     * Emet une demande d'encaissement.
     *
     * <p>Rend un {@link ProviderStatus} et non un simple accuse de reception, parce qu'une
     * initiation a trois issues possibles cote operateur : acceptee et en cours
     * ({@code PENDING}), refusee d'emblee ({@code FAILED}), ou deja aboutie
     * ({@code SUCCEEDED}) chez les operateurs qui traitent en ligne. Les trois sont des
     * reponses, et le meme type les represente.
     *
     * @throws ProviderUnavailableException si l'operateur ne repond pas. La demande est
     *                                      peut-etre parvenue : rien ne permet de conclure
     */
    ProviderStatus initiateCollection(CollectionRequest request);

    /**
     * Demande le statut d'une operation.
     *
     * <p>Interrogeable par notre propre reference autant que par celle de l'operateur :
     * quand le premier appel a expire, on n'a jamais recu de reference operateur, et
     * c'est precisement dans ce cas qu'il faut pouvoir demander.
     *
     * @throws ProviderUnavailableException si l'operateur ne repond pas
     */
    ProviderStatus pollStatus(ProviderCode providerCode, String externalRef, String providerRef);

    record CollectionRequest(ProviderCode providerCode,
                             String externalRef,
                             String idempotencyKey,
                             Money amount,
                             String payerMsisdn,
                             String callbackUrl) {
    }

    record ProviderStatus(Outcome outcome,
                          String providerRef,
                          Money fee,
                          String errorCode,
                          String errorMessage) {

        public static ProviderStatus pending(String providerRef) {
            return new ProviderStatus(Outcome.PENDING, providerRef, null, null, null);
        }
    }

    enum Outcome {
        /** L'operateur a conclu favorablement. */
        SUCCEEDED,
        /** L'operateur a conclu defavorablement. C'est une reponse, pas un silence. */
        FAILED,
        /** L'operateur a repondu qu'il n'a pas encore fini. */
        PENDING
    }

    /**
     * L'operateur n'a pas repondu : delai depasse, connexion impossible, erreur serveur.
     *
     * <p>Ne signifie <b>jamais</b> que l'operation a echoue.
     */
    class ProviderUnavailableException extends RuntimeException {
        public ProviderUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
