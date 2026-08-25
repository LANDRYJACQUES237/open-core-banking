package com.ocb.payment.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.ocb.payment.domain.TransactionStatus.*;

/**
 * Transitions autorisees d'une transaction de paiement.
 *
 * <p><b>C'est le seul endroit du code qui decide si un changement d'etat est legitime.</b>
 * La regle est verifiee par un test d'architecture : aucun {@code if (status == ...)} ne
 * doit exister ailleurs. Un controle d'etat disperse finit toujours par etre applique a un
 * endroit et oublie a un autre, et c'est ainsi qu'un callback duplique passe une fois sur
 * dix.
 *
 * <p><b>Pourquoi une table plutot que des conditions.</b> Une table se lit d'un coup
 * d'oeil, se teste exhaustivement — le test parcourt les 121 paires possibles et compare
 * le comportement reel a la table declaree — et rend visible ce qui manque. Une cascade de
 * {@code if} ne permet aucune des trois choses.
 *
 * <p><b>Ce qui n'est pas une erreur.</b> Recevoir deux fois le meme evenement est normal :
 * Kafka livre au moins une fois et les operateurs rejouent leurs callbacks. Une transition
 * vers l'etat courant n'est donc pas refusee, elle est {@link Decision#IGNORED_ALREADY_THERE
 * ignoree}. Distinguer ce cas d'une transition reellement illegale evite de faire du bruit
 * sur un comportement attendu.
 */
public final class TransactionStateMachine {

    private static final Map<TransactionStatus, Set<TransactionStatus>> ALLOWED =
            new EnumMap<>(TransactionStatus.class);

    static {
        // POSTING est atteignable directement depuis CREATED, et uniquement pour un
        // transfert : il ne passe par aucun operateur, donc par aucun des etats qui
        // decrivent l'attente d'un tiers. Ajouter cette arete n'affaiblit rien —
        // COMPLETED reste inaccessible sans passer par POSTING, ce qui est la garantie
        // qui compte : aucune transaction ne peut se declarer terminee sans qu'une
        // ecriture ait ete tentee.
        ALLOWED.put(CREATED, EnumSet.of(PENDING_PROVIDER, POSTING, FAILED));

        // L'operateur peut refuser d'emblee, ou ne jamais repondre.
        ALLOWED.put(PENDING_PROVIDER, EnumSet.of(PROVIDER_ACCEPTED, PROVIDER_DECLINED, MANUAL_REVIEW));

        ALLOWED.put(PROVIDER_ACCEPTED, EnumSet.of(PROVIDER_CONFIRMED, PROVIDER_DECLINED, MANUAL_REVIEW));

        ALLOWED.put(PROVIDER_CONFIRMED, EnumSet.of(POSTING));

        // Si le grand livre reste injoignable, on ne conclut pas : l'argent a bouge chez
        // l'operateur, la transaction attend un arbitrage.
        ALLOWED.put(POSTING, EnumSet.of(COMPLETED, MANUAL_REVIEW));

        // Un encaissement refuse n'a rien engage : il echoue directement. Un decaissement
        // refuse a deja debite le portefeuille : il doit compenser. La distinction est
        // faite par l'appelant, la machine autorise les deux.
        ALLOWED.put(PROVIDER_DECLINED, EnumSet.of(FAILED, COMPENSATING));

        ALLOWED.put(COMPENSATING, EnumSet.of(REVERSED, MANUAL_REVIEW));

        ALLOWED.put(MANUAL_REVIEW, EnumSet.of(COMPLETED, REVERSED, FAILED, POSTING));

        // Etats terminaux : aucune sortie. C'est cette absence, et non un controle ecrit
        // quelque part, qui neutralise les callbacks tardifs.
        ALLOWED.put(COMPLETED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(TransactionStatus.class));
        ALLOWED.put(REVERSED, EnumSet.noneOf(TransactionStatus.class));
    }

    private TransactionStateMachine() {
    }

    public static Set<TransactionStatus> allowedFrom(TransactionStatus current) {
        return Set.copyOf(ALLOWED.getOrDefault(current, EnumSet.noneOf(TransactionStatus.class)));
    }

    public static boolean canTransition(TransactionStatus from, TransactionStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(TransactionStatus.class)).contains(to);
    }

    /**
     * Statue sur une transition, sans effet de bord.
     *
     * <p>Ne leve jamais d'exception. Un evenement inattendu n'est pas une erreur de
     * programmation : c'est le fonctionnement normal d'un systeme distribue. L'appelant
     * decide quoi en faire — journaliser le refus et acquitter, dans la plupart des cas.
     */
    public static Decision decide(TransactionStatus current, TransactionStatus target) {
        if (current == target) {
            return Decision.IGNORED_ALREADY_THERE;
        }
        if (current.isTerminal()) {
            return Decision.REJECTED_TERMINAL;
        }
        return canTransition(current, target) ? Decision.ACCEPTED : Decision.REJECTED_ILLEGAL;
    }

    public enum Decision {

        ACCEPTED,

        /**
         * Evenement deja pris en compte. Cas nominal d'un doublon Kafka ou d'un callback
         * rejoue par l'operateur.
         */
        IGNORED_ALREADY_THERE,

        /**
         * Transition depuis un etat terminal. Cas du callback tardif : l'operateur
         * confirme une operation deja close de notre cote.
         */
        REJECTED_TERMINAL,

        /** Transition qui n'a pas de sens depuis cet etat. Signale generalement un bug. */
        REJECTED_ILLEGAL;

        public boolean isAccepted() {
            return this == ACCEPTED;
        }

        /** Le message peut etre acquitte : insister ne changerait rien. */
        public boolean isSettled() {
            return this != ACCEPTED;
        }

        public String reason() {
            return switch (this) {
                case ACCEPTED -> null;
                case IGNORED_ALREADY_THERE -> "ALREADY_IN_TARGET_STATE";
                case REJECTED_TERMINAL -> "TERMINAL_STATE";
                case REJECTED_ILLEGAL -> "ILLEGAL_TRANSITION";
            };
        }
    }
}
