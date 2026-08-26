package com.ocb.payment.application;

import com.ocb.payment.domain.PaymentErrors;
import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.TransactionStateMachine;
import com.ocb.payment.domain.TransactionStatus;
import com.ocb.payment.domain.TransactionUpdate;
import com.ocb.payment.domain.port.TransactionStore;
import com.ocb.platform.domain.error.ResourceNotFoundException;
import com.ocb.platform.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Point de passage unique pour tout changement d'etat.
 *
 * <p>Aucune autre classe ne modifie le statut d'une transaction. Cette contrainte est
 * verifiee par un test d'architecture, et elle est ce qui donne sa valeur a la machine a
 * etats : un garde qui peut etre contourne ne garde rien.
 *
 * <p>Le deroulement, dans cet ordre precis :
 *
 * <ol>
 *   <li><b>verrouiller la ligne</b> — sans quoi un callback operateur et le poller de
 *       reconciliation, qui arrivent regulierement ensemble, liraient tous deux l'ancien
 *       etat puis appliqueraient tous deux leur transition ;
 *   <li><b>consulter la machine a etats</b>, qui ne connait ni la base ni Kafka et se
 *       teste donc exhaustivement en memoire ;
 *   <li><b>journaliser la tentative</b>, acceptee ou non. Un refus laisse une trace :
 *       c'est ainsi qu'on demontre qu'un doublon a ete neutralise ;
 *   <li><b>n'appliquer que si la machine a dit oui.</b>
 * </ol>
 */
@Service
public class TransactionStateService {

    private static final Logger log = LoggerFactory.getLogger(TransactionStateService.class);

    private final TransactionStore transactions;

    private final io.micrometer.core.instrument.MeterRegistry meters;

    public TransactionStateService(TransactionStore transactions,
                                   io.micrometer.core.instrument.MeterRegistry meters) {
        this.transactions = transactions;
        this.meters = meters;
    }

    @Transactional
    public Applied apply(UUID transactionId,
                         TransactionStatus target,
                         String triggerEvent,
                         TransactionUpdate update) {

        PaymentTransaction current = transactions.lockById(transactionId).orElseThrow(() ->
                new ResourceNotFoundException(PaymentErrors.TRANSACTION_NOT_FOUND,
                        "Transaction %s introuvable".formatted(transactionId)));

        TransactionStateMachine.Decision decision =
                TransactionStateMachine.decide(current.status(), target);

        transactions.recordTransition(
                transactionId, current.status(), target, triggerEvent,
                decision.isAccepted(), decision.reason(), CorrelationIdFilter.current());

        if (!decision.isAccepted()) {
            // Volontairement en INFO et non en WARN : recevoir deux fois le meme
            // evenement est le fonctionnement normal d'un systeme distribue, pas une
            // anomalie. Alerter dessus noierait les vrais problemes.
            log.info("Transition {} -> {} refusee sur {} ({}), declencheur {}",
                    current.status(), target, transactionId, decision.reason(), triggerEvent);
            return new Applied(decision, current);
        }

        PaymentTransaction updated = transactions.applyTransition(transactionId, target, update);

        // Compte les transitions ACCEPTEES, ici et nulle part ailleurs : c'est le point de
        // passage unique de la machine a etats, donc le seul endroit ou la metrique ne peut
        // pas diverger de la realite. La compter chez les appelants obligerait a se
        // souvenir de le faire a chaque nouveau chemin de code.
        //
        // Deux etiquettes seulement, et aucune de cardinalite non bornee : ni identifiant
        // de transaction, ni reference externe. Une etiquette libre fait exploser le nombre
        // de series temporelles et met Prometheus a genoux.
        meters.counter("ocb.transactions",
                "type", updated.type().name(),
                "status", target.name()).increment();

        log.debug("Transition {} -> {} appliquee sur {}", current.status(), target, transactionId);
        return new Applied(decision, updated);
    }

    @Transactional(readOnly = true)
    public PaymentTransaction require(UUID transactionId) {
        return transactions.find(transactionId).orElseThrow(() ->
                new ResourceNotFoundException(PaymentErrors.TRANSACTION_NOT_FOUND,
                        "Transaction %s introuvable".formatted(transactionId)));
    }

    /**
     * @param transaction etat apres application, ou etat inchange si la transition a ete
     *                    refusee — jamais {@code null}, pour que l'appelant puisse
     *                    toujours repondre quelque chose d'exact
     */
    public record Applied(TransactionStateMachine.Decision decision, PaymentTransaction transaction) {

        public boolean accepted() {
            return decision.isAccepted();
        }
    }
}
