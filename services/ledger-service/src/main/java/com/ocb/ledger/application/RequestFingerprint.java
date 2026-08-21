package com.ocb.ledger.application;

import com.ocb.ledger.domain.EntryLine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.StringJoiner;

/**
 * Empreinte de la requete associee a une cle d'idempotence.
 *
 * <p>Elle sert a distinguer deux situations que l'on ne doit surtout pas confondre :
 *
 * <ul>
 *   <li>un <b>rejeu legitime</b> — meme cle, meme contenu — auquel il faut repondre en
 *       renvoyant la ressource deja creee, sans rien deplacer une seconde fois ;
 *   <li>une <b>reutilisation de cle</b> — meme cle, contenu different — qui signale un
 *       bug appelant. Repondre "d'accord" en renvoyant l'ancienne operation ferait croire
 *       a l'appelant que sa nouvelle demande a ete traitee, alors qu'elle a ete ignoree.
 *       C'est la maniere la plus discrete de perdre un paiement.
 * </ul>
 *
 * <p>L'empreinte porte sur les valeurs <b>normalisees</b> : les montants ont deja traverse
 * {@code Money}, donc {@code "10000"} et {@code "10000.00"} produisent la meme empreinte.
 * Ce sont bien le meme montant, et refuser un rejeu pour une difference d'ecriture
 * decimale serait une fausse alerte.
 *
 * <p>En revanche, les valeurs laissees vides par l'appelant sont prises telles quelles,
 * avant application des defauts : une date de valeur absente reste absente. Sinon, deux
 * rejeux du meme appel a deux jours d'intervalle produiraient des empreintes differentes.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    public static String ofEntry(String rawEntryRef,
                                 String transactionRef,
                                 String description,
                                 LocalDate rawValueDate,
                                 List<EntryLine> lines) {
        StringJoiner canonical = new StringJoiner("|");
        canonical.add(nullSafe(rawEntryRef));
        canonical.add(nullSafe(transactionRef));
        canonical.add(nullSafe(description));
        canonical.add(rawValueDate == null ? "" : rawValueDate.toString());
        for (EntryLine line : lines) {
            canonical.add(line.accountNumber());
            canonical.add(line.direction().name());
            canonical.add(line.amount().toPlainString());
            canonical.add(line.amount().currencyCode());
        }
        return sha256(canonical.toString());
    }

    public static String ofReversal(String reversedEntryRef, String reason, String rawEntryRef) {
        return sha256(String.join("|",
                nullSafe(reversedEntryRef), nullSafe(reason), nullSafe(rawEntryRef)));
    }

    public static String ofAccount(String accountNumber, String accountType, String currency,
                                   String ownerRef, String name) {
        return sha256(String.join("|",
                nullSafe(accountNumber), nullSafe(accountType), nullSafe(currency),
                nullSafe(ownerRef), nullSafe(name)));
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
