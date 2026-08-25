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

    /**
     * Empreinte d'un decaissement.
     *
     * <p>Le sens de l'operation entre dans l'empreinte via le prefixe. Sans lui, un
     * encaissement et un decaissement de memes montant, portefeuille et destinataire
     * produiraient la meme empreinte : rejouer une cle d'idempotence d'un sens sur l'autre
     * passerait pour un rejeu legitime, et rendrait la transaction inverse.
     */
    public static String ofDisbursement(String externalRef,
                                        Money amount,
                                        String walletAccountRef,
                                        String providerCode,
                                        Msisdn payee) {
        return sha256(String.join("|",
                "DISBURSEMENT",
                nullSafe(externalRef),
                amount.toPlainString(),
                amount.currencyCode(),
                nullSafe(walletAccountRef),
                nullSafe(providerCode),
                payee.masked()));
    }

    /**
     * Empreinte d'un transfert.
     *
     * <p>Le sens des portefeuilles entre dans l'empreinte dans l'ordre ou il est donne :
     * un transfert de A vers B et un transfert de B vers A sont deux operations
     * differentes, et rejouer une cle de l'un sur l'autre doit etre refuse.
     */
    public static String ofTransfer(String externalRef,
                                    Money amount,
                                    String fromWalletAccountRef,
                                    String toWalletAccountRef) {
        return sha256(String.join("|",
                "TRANSFER",
                nullSafe(externalRef),
                amount.toPlainString(),
                amount.currencyCode(),
                nullSafe(fromWalletAccountRef),
                nullSafe(toWalletAccountRef)));
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
