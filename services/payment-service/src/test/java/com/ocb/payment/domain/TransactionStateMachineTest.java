package com.ocb.payment.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.ocb.payment.domain.TransactionStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * La machine a etats, verifiee exhaustivement.
 *
 * <p>Un test par l'exemple couvrirait le chemin nominal et deux ou trois refus. Ici, les
 * 121 paires possibles sont parcourues : c'est la seule maniere de garantir qu'aucune
 * transition n'a ete autorisee par inadvertance. Dans un systeme de paiement, une
 * transition oubliee ne se manifeste pas par un plantage mais par un etat incoherent
 * qu'on decouvre au rapprochement comptable.
 */
class TransactionStateMachineTest {

    @ParameterizedTest
    @EnumSource(TransactionStatus.class)
    @DisplayName("aucune transition ne sort d'un etat terminal")
    void terminalStatesHaveNoExit(TransactionStatus status) {
        if (!status.isTerminal()) {
            return;
        }
        assertThat(TransactionStateMachine.allowedFrom(status))
                .as("%s est terminal : plus rien ne doit en sortir", status)
                .isEmpty();
    }

    @ParameterizedTest
    @MethodSource("allPairs")
    @DisplayName("le comportement reel correspond exactement a la table declaree")
    void behaviourMatchesTheDeclaredTable(TransactionStatus from, TransactionStatus to) {
        TransactionStateMachine.Decision decision = TransactionStateMachine.decide(from, to);

        if (from == to) {
            assertThat(decision)
                    .as("recevoir deux fois le meme evenement est normal, pas une erreur")
                    .isEqualTo(TransactionStateMachine.Decision.IGNORED_ALREADY_THERE);
            return;
        }
        if (from.isTerminal()) {
            assertThat(decision).isEqualTo(TransactionStateMachine.Decision.REJECTED_TERMINAL);
            return;
        }
        assertThat(decision.isAccepted())
                .as("%s -> %s", from, to)
                .isEqualTo(TransactionStateMachine.canTransition(from, to));
    }

    @Test
    @DisplayName("le chemin d'un transfert court-circuite l'operateur, mais pas POSTING")
    void transferPathSkipsTheProviderButNotPosting() {
        // Un transfert entre portefeuilles n'appelle aucun operateur : il n'a rien a faire
        // dans les etats qui decrivent l'attente d'un tiers.
        assertThat(TransactionStateMachine.canTransition(CREATED, POSTING)).isTrue();
        assertThat(TransactionStateMachine.canTransition(POSTING, COMPLETED)).isTrue();

        // Ce que ce raccourci ne doit pas ouvrir : se declarer termine sans avoir tente
        // la moindre ecriture.
        assertThat(TransactionStateMachine.canTransition(CREATED, COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("le chemin nominal d'un encaissement est autorise de bout en bout")
    void nominalCollectionPath() {
        List<TransactionStatus> path = List.of(
                CREATED, PENDING_PROVIDER, PROVIDER_ACCEPTED, PROVIDER_CONFIRMED, POSTING, COMPLETED);

        for (int i = 0; i < path.size() - 1; i++) {
            assertThat(TransactionStateMachine.canTransition(path.get(i), path.get(i + 1)))
                    .as("%s -> %s", path.get(i), path.get(i + 1))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("un callback tardif sur une transaction terminee est refuse, pas applique")
    void lateCallbackOnCompletedIsRejected() {
        // Cas reel et frequent : l'operateur rejoue son callback des heures apres, alors
        // que le polling avait deja tranche. L'absence de transition sortante de COMPLETED
        // suffit a le neutraliser — aucun controle ecrit ailleurs n'est necessaire.
        TransactionStateMachine.Decision decision =
                TransactionStateMachine.decide(COMPLETED, PROVIDER_CONFIRMED);

        assertThat(decision).isEqualTo(TransactionStateMachine.Decision.REJECTED_TERMINAL);
        assertThat(decision.reason()).isEqualTo("TERMINAL_STATE");
        assertThat(decision.isSettled())
                .as("insister ne changerait rien : le message peut etre acquitte")
                .isTrue();
    }

    @Test
    @DisplayName("un callback duplique est ignore, et distingue d'une transition illegale")
    void duplicateCallbackIsIgnoredNotRejected() {
        // La nuance a une consequence operationnelle : un doublon est le fonctionnement
        // normal d'un bus au moins une fois, alors qu'une transition illegale signale un
        // bug. Les confondre ferait alerter sur l'un ou taire l'autre.
        assertThat(TransactionStateMachine.decide(PROVIDER_ACCEPTED, PROVIDER_ACCEPTED))
                .isEqualTo(TransactionStateMachine.Decision.IGNORED_ALREADY_THERE);

        assertThat(TransactionStateMachine.decide(CREATED, COMPLETED))
                .isEqualTo(TransactionStateMachine.Decision.REJECTED_ILLEGAL);
    }

    @Test
    @DisplayName("on ne peut pas sauter l'ecriture comptable pour aller a COMPLETED")
    void cannotSkipLedgerPosting() {
        // Sans cette interdiction, un bug pourrait declarer une transaction terminee sans
        // qu'aucune ecriture n'existe : l'argent aurait bouge chez l'operateur sans jamais
        // apparaitre au grand livre.
        assertThat(TransactionStateMachine.canTransition(PROVIDER_CONFIRMED, COMPLETED)).isFalse();
        assertThat(TransactionStateMachine.canTransition(PROVIDER_ACCEPTED, COMPLETED)).isFalse();
        assertThat(TransactionStateMachine.canTransition(POSTING, COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("une absence de reponse mene en revue manuelle, jamais en echec")
    void unresolvedNeverGoesToFailed() {
        // La faute la plus couteuse d'un systeme de paiement serait de transformer une
        // incertitude en certitude fausse. Depuis les etats ou l'operateur a ete sollicite,
        // MANUAL_REVIEW doit rester accessible et FAILED ne doit pas l'etre directement.
        assertThat(TransactionStateMachine.canTransition(PENDING_PROVIDER, MANUAL_REVIEW)).isTrue();
        assertThat(TransactionStateMachine.canTransition(PROVIDER_ACCEPTED, MANUAL_REVIEW)).isTrue();
        assertThat(TransactionStateMachine.canTransition(POSTING, MANUAL_REVIEW)).isTrue();

        assertThat(TransactionStateMachine.canTransition(PENDING_PROVIDER, FAILED))
                .as("un silence de l'operateur ne permet pas de conclure a l'echec")
                .isFalse();
        assertThat(TransactionStateMachine.canTransition(PROVIDER_ACCEPTED, FAILED)).isFalse();
    }

    @Test
    @DisplayName("un refus definitif de l'operateur peut mener a l'echec ou a la compensation")
    void declinedCanFailOrCompensate() {
        // Encaissement : rien n'a ete engage, on echoue. Decaissement : le portefeuille a
        // ete debite avant l'appel, il faut compenser. La machine autorise les deux, le
        // flux choisit.
        assertThat(TransactionStateMachine.canTransition(PROVIDER_DECLINED, FAILED)).isTrue();
        assertThat(TransactionStateMachine.canTransition(PROVIDER_DECLINED, COMPENSATING)).isTrue();
        assertThat(TransactionStateMachine.canTransition(COMPENSATING, REVERSED)).isTrue();
    }

    @Test
    @DisplayName("tous les etats non terminaux ont au moins une sortie")
    void noDeadEnds() {
        // Un etat non terminal sans sortie serait un piege : les transactions s'y
        // accumuleraient sans qu'aucun mecanisme ne puisse les en faire sortir.
        for (TransactionStatus status : TransactionStatus.values()) {
            if (status.isTerminal()) {
                continue;
            }
            assertThat(TransactionStateMachine.allowedFrom(status))
                    .as("%s n'a aucune sortie : les transactions y resteraient bloquees", status)
                    .isNotEmpty();
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> allPairs() {
        List<org.junit.jupiter.params.provider.Arguments> pairs = new ArrayList<>();
        for (TransactionStatus from : TransactionStatus.values()) {
            for (TransactionStatus to : TransactionStatus.values()) {
                pairs.add(org.junit.jupiter.params.provider.Arguments.of(from, to));
            }
        }
        return pairs.stream();
    }
}
