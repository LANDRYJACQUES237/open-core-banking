package com.ocb.payment.domain.port;

import com.ocb.platform.domain.money.Money;

import java.util.List;

/**
 * Acces au grand livre.
 *
 * <p>Appel <b>synchrone</b>, et c'est un choix. Le grand livre doit rendre un verdict
 * immediat — solde insuffisant, compte gele — qu'une commande asynchrone transformerait
 * en flux de compensation pour une situation parfaitement ordinaire.
 *
 * <p>L'objection est connue : un appel reseau suivi d'un commit local est un dual-write.
 * Elle est levee par l'idempotence. Si le grand livre enregistre l'ecriture puis que ce
 * service tombe avant de valider, le message sera redelivre, la meme cle d'idempotence
 * sera rejouee, et le grand livre rendra l'ecriture <b>existante</b> au lieu d'en creer
 * une seconde. L'etat converge sans qu'aucun second mouvement d'argent n'ait lieu.
 */
public interface LedgerPort {

    /**
     * Enregistre une ecriture.
     *
     * @return la reference de l'ecriture, qu'elle vienne d'etre creee ou qu'elle existait deja
     * @throws com.ocb.platform.domain.error.InvariantViolationException si le grand livre refuse
     * @throws LedgerUnavailableException si le grand livre est injoignable — cas a ne
     *                                    surtout pas confondre avec un refus : rien ne
     *                                    permet alors de conclure que l'ecriture n'a pas
     *                                    eu lieu
     */
    String post(EntryRequest request);

    /**
     * Solde d'un compte, exprime dans le sens normal de ce compte.
     *
     * <p>Pour un portefeuille client — un compte de passif — un solde positif signifie
     * donc que le client a de l'argent chez nous.
     *
     * <p>La valeur rendue est <b>faisant foi</b> et non approchee : le grand livre part de
     * son instantane puis ajoute les ecritures posterieures. L'instantane n'est qu'un
     * cache, le retirer donnerait le meme resultat, seulement plus lentement. C'est ce qui
     * autorise a decider d'un decaissement sur cette lecture.
     *
     * @throws LedgerUnavailableException si le grand livre est injoignable. Ne jamais
     *                                    interpreter ce cas comme un solde nul : ce serait
     *                                    refuser des demandes parfaitement financables
     */
    Money balanceOf(String accountNumber);

    /**
     * Contre-passe une ecriture.
     *
     * <p>C'est la compensation de la saga. Elle ne modifie ni ne supprime l'originale — le
     * grand livre est immuable : elle ajoute une ecriture de sens inverse qui la designe.
     *
     * <p>L'operation est idempotente de deux facons qui se recouvrent : la cle
     * d'idempotence rend le rejeu de la <b>meme</b> compensation inoffensif, et l'unicite
     * sur l'ecriture d'origine interdit qu'elle soit contre-passee deux fois. La premiere
     * evite une erreur sur un chemin legitime, la seconde est le garde-fou definitif.
     *
     * @return la reference de l'ecriture de compensation
     */
    String reverse(ReversalRequest request);

    record ReversalRequest(String originalEntryRef,
                           String idempotencyKey,
                           String reason) {
    }

    /**
     * @param entryRef reference fonctionnelle de l'ecriture, ou {@code null} pour laisser
     *                 le grand livre la generer. La fournir rend la reference
     *                 <b>derivable</b> plutot que retrouvable : une saga qui doit
     *                 compenser sait quelle ecriture contre-passer a partir du seul
     *                 identifiant de transaction, sans avoir a l'avoir conservee — donc
     *                 sans qu'un oubli de persistance puisse la lui faire perdre
     */
    record EntryRequest(String idempotencyKey,
                        String entryRef,
                        String transactionRef,
                        String description,
                        List<Line> lines) {
    }

    record Line(String accountNumber, String direction, Money amount) {

        public static Line debit(String accountNumber, Money amount) {
            return new Line(accountNumber, "DR", amount);
        }

        public static Line credit(String accountNumber, Money amount) {
            return new Line(accountNumber, "CR", amount);
        }
    }

    /** Le grand livre n'a pas repondu. On ne sait pas si l'ecriture a eu lieu. */
    class LedgerUnavailableException extends RuntimeException {
        public LedgerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
