package com.ocb.payment.application;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Identifiant de transaction derive de la demande, et non tire au hasard.
 *
 * <p><b>La fenetre que cela ferme.</b> Une operation qui ecrit au grand livre <b>avant</b>
 * de valider sa propre transaction fait une double ecriture : l'ecriture comptable est
 * validee chez le grand livre, la notre ne l'est pas encore. Si le processus meurt entre
 * les deux, notre transaction est annulee — reservation de cle d'idempotence comprise —
 * mais l'ecriture comptable, elle, subsiste.
 *
 * <p>Le client, qui a vu un timeout, rejoue avec la meme cle. La reservation ayant ete
 * annulee, la demande repart comme neuve. Avec un identifiant tire au hasard, elle
 * produirait une <b>seconde</b> ecriture, et le portefeuille du client serait debite deux
 * fois. Rien ne le signalerait : les deux ecritures sont equilibrees et legitimes prises
 * separement.
 *
 * <p>En derivant l'identifiant de l'identite de la demande, le rejeu retombe sur le meme
 * identifiant, donc sur la meme cle d'idempotence et la meme reference d'ecriture. Le
 * grand livre reconnait alors l'ecriture existante et la rend telle quelle. L'etat
 * converge au lieu de diverger.
 *
 * <p><b>Ou l'appliquer.</b> Uniquement la ou un effet externe precede la validation locale
 * — decaissement, transfert. Un encaissement n'appelle personne avant de valider : son
 * annulation ne laisse aucune trace, et un identifiant aleatoire y reste correct. Etendre
 * cette mecanique la ou elle ne sert a rien laisserait croire qu'elle protege d'autre
 * chose.
 */
public final class RequestIdentity {

    private RequestIdentity() {
    }

    /**
     * Meme appelant, meme cle, meme identifiant de transaction.
     *
     * <p>L'appelant entre dans le calcul : deux marchands qui choisissent la meme cle
     * doivent obtenir deux transactions distinctes, exactement comme pour la portee
     * d'idempotence.
     *
     * <p>Une meme cle rejouee avec un <b>contenu different</b> ne parvient jamais jusqu'ici :
     * l'empreinte de requete la rejette en amont. La collision d'identifiants qu'on
     * pourrait craindre est donc deja exclue.
     */
    public static UUID of(String clientId, String idempotencyKey) {
        byte[] digest = sha256(clientId + "|" + idempotencyKey);

        // Les 16 premiers octets du condensat, mis a la forme d'un UUID de version 4.
        // SHA-256 plutot que le UUID de version 3 de la bibliotheque standard, qui repose
        // sur MD5 : le resultat serait le meme ici, mais un condensat casse dans un
        // systeme de paiement est une explication qu'on n'a pas envie d'avoir a donner.
        ByteBuffer buffer = ByteBuffer.wrap(digest, 0, 16);
        long high = buffer.getLong();
        long low = buffer.getLong();

        high = (high & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_4000L;
        low = (low & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;

        return new UUID(high, low);
    }

    private static byte[] sha256(String canonical) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }
}
