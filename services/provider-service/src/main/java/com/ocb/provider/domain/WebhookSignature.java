package com.ocb.provider.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Verification de la signature d'un rappel entrant.
 *
 * <p>C'est la seule surface publique de la plateforme : ce qui arrive ici vient
 * d'Internet, et rien ne prouve a priori que l'appelant est l'operateur.
 *
 * <p>Quatre precautions, chacune contre une attaque precise.
 *
 * <p><b>La signature porte sur le corps brut</b>, avant tout parsing. Signer la forme
 * reserialisee laisserait passer une alteration que le parseur normalise — un espace, un
 * ordre de champs, un nombre ecrit autrement. Le corps brut est le seul octet a octet.
 *
 * <p><b>L'horodatage entre dans la signature.</b> Sans lui, une signature valide capturee
 * une fois resterait utilisable indefiniment : il suffirait de rejouer la requete pour
 * rejouer le paiement.
 *
 * <p><b>La fenetre de rejeu est bornee.</b> Meme signee, une requete trop ancienne est
 * refusee. Cela limite la valeur d'une capture reseau a quelques minutes.
 *
 * <p><b>La comparaison est a temps constant.</b> Une comparaison de chaines ordinaire
 * s'arrete au premier octet different : le temps de reponse revele alors combien d'octets
 * etaient corrects, et permet de reconstituer une signature valide octet par octet.
 */
public final class WebhookSignature {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private WebhookSignature() {
    }

    /**
     * @param signatureHeader valeur de l'en-tete, de la forme {@code sha256=<hexadecimal>}
     * @param timestampHeader horodatage Unix en secondes, tel que recu
     * @param rawBody         corps de la requete, exactement tel que transmis
     */
    public static Verdict verify(String signatureHeader,
                                 String timestampHeader,
                                 String rawBody,
                                 String secret,
                                 Duration replayWindow,
                                 Instant now) {

        if (signatureHeader == null || timestampHeader == null || rawBody == null) {
            return Verdict.MISSING;
        }

        long epochSeconds;
        try {
            epochSeconds = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return Verdict.MISSING;
        }

        Instant sentAt = Instant.ofEpochSecond(epochSeconds);
        // Fenetre symetrique : une horloge d'operateur peut aussi bien avancer que
        // retarder. Refuser le futur proche rendrait le service dependant d'une
        // synchronisation parfaite entre deux systemes qu'on ne controle pas.
        if (Duration.between(sentAt, now).abs().compareTo(replayWindow) > 0) {
            return Verdict.EXPIRED;
        }

        String expected = PREFIX + hmacHex(secret, epochSeconds + "." + rawBody);
        return constantTimeEquals(expected, signatureHeader.trim())
                ? Verdict.VALID
                : Verdict.INVALID;
    }

    /** Expose le calcul pour que les tests puissent produire des signatures legitimes. */
    public static String sign(String secret, long epochSeconds, String rawBody) {
        return PREFIX + hmacHex(secret, epochSeconds + "." + rawBody);
    }

    private static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Calcul HMAC impossible", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    public enum Verdict {
        VALID,
        /** Signature presente mais incorrecte : l'appelant n'est pas l'operateur. */
        INVALID,
        /** Signature correcte mais hors de la fenetre de rejeu. */
        EXPIRED,
        /** En-tete absent ou horodatage illisible. */
        MISSING;

        public boolean isValid() {
            return this == VALID;
        }
    }
}
