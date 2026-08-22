package com.ocb.payment.domain.port;

import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.StateTransitionRecord;
import com.ocb.payment.domain.TransactionStatus;
import com.ocb.payment.domain.TransactionUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionStore {

    PaymentTransaction create(PaymentTransaction transaction);

    Optional<PaymentTransaction> find(UUID id);

    /**
     * Charge la transaction en verrouillant sa ligne jusqu'a la fin de la transaction
     * courante.
     *
     * <p>C'est ce verrou, et rien d'autre, qui rend correcte la course entre un callback
     * operateur et le poller de reconciliation : les deux arrivent regulierement en meme
     * temps pour la meme transaction, et sans serialisation ils liraient tous deux
     * l'ancien etat, puis appliqueraient tous deux leur transition.
     *
     * <p>Verrou pessimiste plutot qu'optimiste : la contention porte sur une seule ligne
     * et dure quelques millisecondes, alors qu'un verrou optimiste imposerait de rejouer
     * un traitement qui a deja appele le grand livre.
     */
    Optional<PaymentTransaction> lockById(UUID id);

    /** Applique une transition deja validee par la machine a etats. */
    PaymentTransaction applyTransition(UUID id, TransactionStatus target, TransactionUpdate update);

    /**
     * Journalise une tentative de transition, acceptee ou refusee.
     *
     * <p>Appele dans les deux cas : un refus laisse une trace exploitable, c'est ce qui
     * permet de demontrer qu'un callback duplique a bien ete neutralise.
     */
    void recordTransition(UUID transactionId,
                          TransactionStatus from,
                          TransactionStatus to,
                          String triggerEvent,
                          boolean accepted,
                          String rejectionReason,
                          String correlationId);

    List<StateTransitionRecord> transitionsOf(UUID transactionId);
}
