package com.ocb.payment;

import com.ocb.payment.application.DisbursementCommand;
import com.ocb.payment.application.DisbursementService;
import com.ocb.payment.domain.ProviderCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Le rejeu apres une panne survenue entre l'ecriture comptable et la validation locale.
 *
 * <p><b>La situation.</b> Un decaissement ecrit au grand livre — service distant, qui
 * valide immediatement — puis termine sa propre transaction. Entre les deux, il existe une
 * fenetre. Si le processus meurt la, notre transaction est annulee, reservation de cle
 * d'idempotence comprise, tandis que l'ecriture comptable subsiste chez le grand livre.
 *
 * <p>Le client, qui a vu un timeout, rejoue avec la meme cle. Il ne trouve plus aucune
 * reservation : sa demande repart comme neuve. C'est precisement la que tout se joue. Avec
 * un identifiant de transaction tire au hasard, le rejeu produirait une <b>seconde</b>
 * ecriture d'engagement et le portefeuille serait debite deux fois — sans que rien ne le
 * signale, les deux ecritures etant equilibrees et legitimes prises separement.
 *
 * <p><b>Comment la panne est simulee.</b> La demande est executee dans une transaction
 * marquee pour annulation. Tout ce qui est local disparait ; la doublure de grand livre,
 * elle, n'y participe pas — c'est sa propriete essentielle, et c'est aussi le comportement
 * d'un vrai appel REST. L'etat obtenu est exactement celui d'un arret brutal, en
 * reproductible.
 *
 * <p>Effacer les lignes a la main n'aurait pas fonctionne, et pour une bonne raison : la
 * table des transitions est rendue immuable par declencheur. Le systeme refuse qu'on
 * reecrive son historique, y compris dans un test.
 */
class DisbursementCrashRecoveryIT extends StubbedLedgerTestBase {

    private static final String AMOUNT = "5000";
    private static final String FEE = "50";
    private static final String FUNDS = "20000";

    @Autowired
    private DisbursementService disbursements;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void fundTheWallet() {
        stub.credit(wallet, FUNDS);
    }

    @Test
    @DisplayName("le rejeu apres annulation locale ne debite pas le portefeuille deux fois")
    void replayAfterRollbackDoesNotDebitTwice() {
        DisbursementCommand command = command("disb-panne-" + suffix);
        BigDecimal expectedAfterOneDebit = new BigDecimal(FUNDS)
                .subtract(new BigDecimal(AMOUNT)).subtract(new BigDecimal(FEE));

        String abandonedTransactionId = new TransactionTemplate(transactionManager).execute(status -> {
            DisbursementService.Result result = disbursements.request(command);
            // Le processus meurt ici : le grand livre a valide, nous non.
            status.setRollbackOnly();
            return result.transaction().id().toString();
        });

        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("l'ecriture comptable a bien survecu a l'annulation locale")
                .isEqualByComparingTo(expectedAfterOneDebit);
        assertThat(transactionExists(abandonedTransactionId))
                .as("cote paiement, l'annulation n'a rien laisse")
                .isFalse();

        // Le client rejoue avec la meme cle. Rien ne subsiste chez nous : la demande
        // repart comme neuve.
        DisbursementService.Result replay = disbursements.request(command("disb-panne-" + suffix));

        // Le prejudice d'abord : si ce test casse un jour, le message doit dire que de
        // l'argent est sorti deux fois, pas que deux identifiants different.
        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("le portefeuille n'a ete debite qu'une fois, malgre deux demandes abouties")
                .isEqualByComparingTo(expectedAfterOneDebit);

        long reservations = stub.postedEntries().stream()
                .filter(entry -> entry.lines().stream()
                        .anyMatch(line -> wallet.equals(line.accountNumber())))
                .count();
        assertThat(reservations)
                .as("le grand livre a reconnu son ecriture au lieu d'en creer une seconde")
                .isEqualTo(1);

        // Le mecanisme ensuite, qui explique le resultat ci-dessus.
        assertThat(replay.transaction().id().toString())
                .as("meme appelant, meme cle : le meme identifiant de transaction")
                .isEqualTo(abandonedTransactionId);
    }

    @Test
    @DisplayName("deux appelants qui choisissent la meme cle obtiennent deux transactions")
    void sameKeyDifferentCallersStayDistinct() {
        // Contre-epreuve du derivage : si l'identifiant ne dependait que de la cle, deux
        // marchands utilisant des compteurs se retrouveraient sur la meme transaction.
        String key = "compteur-1-" + suffix;

        DisbursementService.Result a = disbursements.request(
                command(key, "marchand-A", "TX-A-" + suffix));
        DisbursementService.Result b = disbursements.request(
                command(key, "marchand-B", "TX-B-" + suffix));

        assertThat(b.transaction().id()).isNotEqualTo(a.transaction().id());
    }

    // --- Utilitaires -------------------------------------------------------------------

    private DisbursementCommand command(String idempotencyKey) {
        return command(idempotencyKey, "marchand", "TX-" + suffix);
    }

    private DisbursementCommand command(String idempotencyKey, String clientId, String externalRef) {
        return new DisbursementCommand(externalRef, AMOUNT, "XAF", "+237670000001",
                wallet, ProviderCode.MTN_MOMO, idempotencyKey, clientId, "corr-" + suffix);
    }

    private boolean transactionExists(String transactionId) {
        Long count = jdbc.sql("SELECT COUNT(*) FROM payment.payment_transaction WHERE id = :id")
                .param("id", java.util.UUID.fromString(transactionId))
                .query(Long.class)
                .single();
        return count != null && count > 0;
    }
}
