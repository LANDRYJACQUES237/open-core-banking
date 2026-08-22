package com.ocb.payment.application;

import com.ocb.payment.domain.Msisdn;
import com.ocb.platform.domain.money.Money;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Empreinte de la requete associee a une cle d'idempotence.
 *
 * <p>Elle distingue deux situations qu'il ne faut surtout pas confondre : un rejeu
 * legitime, auquel on repond en rendant l'operation deja enregistree, et une reutilisation
 * de cle pour une demande differente, qui est un bug appelant. Repondre "d'accord" au
 * second ferait croire a l'appelant que sa nouvelle demande a ete traitee alors qu'elle a
 * ete ignoree — la maniere la plus discrete de perdre un paiement.
 *
 * <p>L'empreinte porte sur des valeurs <b>normalisees</b> : les montants ont traverse
 * {@link Money}, donc {@code "10000"} et {@code "10000.00"} donnent la meme empreinte. Ce
 * sont le meme montant, et refuser un rejeu pour une difference d'ecriture decimale serait
 * une fausse alerte qui pousserait l'appelant a contourner l'idempotence.
 *
 * <p>Le numero du payeur entre dans l'empreinte sous sa forme <b>masquee</b>. Deux numeros
 * differents partageant les memes quatre derniers chiffres produiraient donc la meme
 * empreinte — un cas suffisamment improbable, et de toute facon protege par le fait que la
 * cle d'idempotence est choisie par l'appelant. En contrepartie, aucun numero complet ne
 * se retrouve dans une valeur persistee.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String ofCollection(String externalRef,
                                      Money amount,
                                      String walletAccountRef,
                                      String providerCode,
                                      Msisdn payer) {
        return sha256(String.join("|",
                nullSafe(externalRef),
                amount.toPlainString(),
                amount.currencyCode(),
                nullSafe(walletAccountRef),
                nullSafe(providerCode),
                payer.masked()));
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
