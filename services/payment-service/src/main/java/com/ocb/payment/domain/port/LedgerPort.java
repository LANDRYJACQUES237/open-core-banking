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

    record EntryRequest(String idempotencyKey,
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
