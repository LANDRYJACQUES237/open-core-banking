package com.ocb.payment;

import com.ocb.payment.application.ProviderOutcomeService;
import com.ocb.payment.domain.DisbursementEntryRefs;
import com.ocb.payment.domain.port.LedgerPort;
import com.ocb.payment.support.LedgerStub;
import com.ocb.platform.events.Payloads;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La saga de decaissement, de bout en bout.
 *
 * <p>Ce qui est verifie ici n'est pas qu'un decaissement "marche", mais que
 * <b>l'engagement des fonds precede l'appel a l'operateur</b> et que chacune des issues
 * possibles laisse le portefeuille du client dans un etat defendable :
 *
 * <ul>
 *   <li>l'operateur livre : les fonds quittent le compte de passage vers le float ;
 *   <li>l'operateur refuse : les fonds reviennent integralement au client, frais compris ;
 *   <li>l'operateur ne conclut pas : <b>rien ne bouge</b>, et un humain tranche.
 * </ul>
 *
 * <p>Le troisieme cas est le plus important, et c'est celui qu'une saga naive rate : elle
 * compense des qu'elle n'a pas de confirmation, et rembourse ainsi des beneficiaires deja
 * payes.
 */
@Import(com.ocb.payment.support.LedgerStubConfiguration.class)
class DisbursementSagaIT extends PaymentPersistenceTestBase {

    private static final String AMOUNT = "5000";
    private static final String FEE = "50";
    private static final String FUNDS = "20000";

    @Autowired
    private LedgerPort ledger;

    @Autowired
    private ProviderOutcomeService outcomes;

    private LedgerStub stub;
    private String wallet;

    @BeforeEach
    void fundTheWallet() {
        stub = (LedgerStub) ledger;
        wallet = "2100.wallet-" + suffix;
        stub.credit(wallet, FUNDS);
    }

    @Test
    @DisplayName("l'engagement des fonds precede l'appel a l'operateur")
    void fundsAreCommittedBeforeTheOperatorIsCalled() {
        ApiResponse response = requestDisbursement();

        assertThat(response.status()).isEqualTo(202);
        assertThat(response.status_()).isEqualTo("PENDING_PROVIDER");

        // Le portefeuille est deja debite du montant augmente des frais, alors qu'aucun
        // operateur n'a encore ete appele. C'est toute la difference avec un encaissement,
        // et c'est ce qui rend une compensation necessaire.
        assertThat(stub.balanceOfWallet(wallet).amount())
                .isEqualByComparingTo(new BigDecimal(FUNDS)
                        .subtract(new BigDecimal(AMOUNT)).subtract(new BigDecimal(FEE)));

        UUID transactionId = UUID.fromString(response.transactionId());
        assertThat(stub.countEntriesWithRef(DisbursementEntryRefs.reservation(transactionId)))
                .isEqualTo(1);

        // L'ordre part par l'outbox, dans la meme transaction que l'ecriture : jamais par
        // un appel direct, qui rouvrirait le dual-write.
        assertThat(outboxCount(response.transactionId(), "provider.disbursement.execute"))
                .isEqualTo(1);
        assertThat(outboxCount(response.transactionId(), "payment.disbursement.requested"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("l'operateur livre : le compte de passage se solde, la transaction se termine")
    void deliveredDisbursementSettles() {
        ApiResponse response = requestDisbursement();
        UUID transactionId = UUID.fromString(response.transactionId());
        BigDecimal afterReservation = stub.balanceOfWallet(wallet).amount();

        // L'accuse de reception precede la confirmation, et la machine a etats l'exige :
        // PROVIDER_CONFIRMED n'est pas atteignable depuis PENDING_PROVIDER. Ce n'est pas
        // une formalite de test — provider-service publie toujours les deux, dans cet
        // ordre, y compris quand l'operateur conclut en ligne.
        outcomes.onAccepted(accepted(transactionId), "corr-" + suffix);
        outcomes.onSucceeded(succeeded(transactionId), "corr-" + suffix);

        assertThat(statusOf(transactionId)).isEqualTo("COMPLETED");
        assertThat(stub.countEntriesWithRef(DisbursementEntryRefs.settlement(transactionId)))
                .isEqualTo(1);

        // La livraison ne touche pas au portefeuille : le client a ete debite a
        // l'engagement, pas deux fois.
        assertThat(stub.balanceOfWallet(wallet).amount()).isEqualByComparingTo(afterReservation);
        assertThat(outboxCount(response.transactionId(), "payment.disbursement.completed"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("l'operateur refuse : le client est rembourse integralement, frais compris")
    void declinedDisbursementIsCompensated() {
        ApiResponse response = requestDisbursement();
        UUID transactionId = UUID.fromString(response.transactionId());

        outcomes.onFailed(failed(transactionId, "PAYEE_UNKNOWN"), "corr-" + suffix);

        assertThat(statusOf(transactionId))
                .as("REVERSED et non FAILED : de l'argent avait bouge, il a fallu le rendre")
                .isEqualTo("REVERSED");

        // Le point qui merite le test : les frais de plateforme sont rendus eux aussi.
        // Facturer la prise en charge d'un ordre que l'operateur a refuse serait
        // indefendable, et la contre-passation le garantit en inversant les trois lignes.
        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("le portefeuille retrouve exactement son solde d'origine")
                .isEqualByComparingTo(new BigDecimal(FUNDS));

        assertThat(outboxCount(response.transactionId(), "payment.disbursement.reversed"))
                .isEqualTo(1);
        assertThat(outboxCount(response.transactionId(), "payment.collection.failed"))
                .as("un decaissement ne publie jamais l'echec d'un encaissement")
                .isZero();
    }

    @Test
    @DisplayName("l'operateur ne conclut pas : aucune compensation, arbitrage humain")
    void unresolvedDisbursementIsNeverCompensated() {
        ApiResponse response = requestDisbursement();
        UUID transactionId = UUID.fromString(response.transactionId());
        BigDecimal afterReservation = stub.balanceOfWallet(wallet).amount();

        outcomes.onUnresolved(unresolved(transactionId), "corr-" + suffix);

        assertThat(statusOf(transactionId)).isEqualTo("MANUAL_REVIEW");

        // Le coeur de la decision : ne pas compenser. L'operateur a peut-etre paye le
        // beneficiaire. Rembourser le client ici ferait sortir l'argent deux fois, et
        // aucune ecriture ne le signalerait.
        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("les fonds restent en compte de passage : c'est une question ouverte, "
                        + "pas une conclusion")
                .isEqualByComparingTo(afterReservation);

        assertThat(outboxCount(response.transactionId(), "payment.disbursement.reversed")).isZero();
        assertThat(outboxCount(response.transactionId(),
                "payment.transaction.manual_review_required")).isEqualTo(1);
    }

    @Test
    @DisplayName("grand livre injoignable pendant la compensation : rien n'est conclu")
    void unreachableLedgerDuringCompensationConcludesNothing() {
        ApiResponse response = requestDisbursement();
        UUID transactionId = UUID.fromString(response.transactionId());

        stub.becomeUnavailable();

        // L'exception doit remonter : c'est elle qui annule la transaction locale et fait
        // redelivrer le message. Avaler l'erreur laisserait la transaction en COMPENSATING
        // pour toujours, sans que personne ne reessaie.
        assertThatThrownBy(() -> outcomes.onFailed(failed(transactionId, "PAYEE_UNKNOWN"),
                "corr-" + suffix))
                .isInstanceOf(LedgerPort.LedgerUnavailableException.class);

        assertThat(statusOf(transactionId))
                .as("l'annulation emporte aussi le passage en COMPENSATING : la "
                        + "redelivrance repartira d'un etat coherent")
                .isEqualTo("PENDING_PROVIDER");
    }

    @Test
    @DisplayName("solde insuffisant : refus net, aucune ecriture, aucun ordre")
    void insufficientFundsChangesNothing() {
        long entriesBefore = stub.postedEntries().size();

        ApiResponse response = post("/v1/disbursements", "disb-trop-" + suffix,
                disbursementBody("999999", wallet));

        assertThat(response.status()).isEqualTo(422);
        assertThat(response.code()).isEqualTo("PAYMENT_INSUFFICIENT_FUNDS");
        assertThat(stub.balanceOfWallet(wallet).amount()).isEqualByComparingTo(new BigDecimal(FUNDS));
        assertThat(stub.postedEntries()).hasSize((int) entriesBefore);
    }

    @Test
    @DisplayName("rejouer la cle rend la transaction existante, sans engager deux fois")
    void replayDoesNotCommitTwice() {
        String key = "disb-rejeu-" + suffix;
        String body = disbursementBody(AMOUNT, wallet);

        ApiResponse first = post("/v1/disbursements", key, body);
        ApiResponse replay = post("/v1/disbursements", key, body);

        assertThat(first.status()).isEqualTo(202);
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.transactionId()).isEqualTo(first.transactionId());

        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("le portefeuille n'est debite qu'une fois")
                .isEqualByComparingTo(new BigDecimal(FUNDS)
                        .subtract(new BigDecimal(AMOUNT)).subtract(new BigDecimal(FEE)));
    }

    // --- Utilitaires -------------------------------------------------------------------

    private ApiResponse requestDisbursement() {
        return post("/v1/disbursements", "disb-" + suffix + "-" + UUID.randomUUID(),
                disbursementBody(AMOUNT, wallet));
    }

    private String statusOf(UUID transactionId) {
        return jdbc.sql("SELECT status FROM payment.payment_transaction WHERE id = :id")
                .param("id", transactionId)
                .query(String.class)
                .single();
    }

    private Payloads.ProviderOperationAccepted accepted(UUID transactionId) {
        return new Payloads.ProviderOperationAccepted(
                transactionId.toString(), "MTN_MOMO", "MTN-REF-" + suffix, Instant.now());
    }

    private Payloads.ProviderOperationSucceeded succeeded(UUID transactionId) {
        return new Payloads.ProviderOperationSucceeded(
                transactionId.toString(), "MTN_MOMO", "MTN-REF-" + suffix,
                "25", "XAF", Instant.now(), "CALLBACK");
    }

    private Payloads.ProviderOperationFailed failed(UUID transactionId, String code) {
        return new Payloads.ProviderOperationFailed(
                transactionId.toString(), "MTN_MOMO", "MTN-REF-" + suffix,
                code, "Beneficiaire inconnu", "CALLBACK");
    }

    private Payloads.ProviderOperationUnresolved unresolved(UUID transactionId) {
        return new Payloads.ProviderOperationUnresolved(
                transactionId.toString(), "MTN_MOMO", "MTN-REF-" + suffix, 12, "PENDING");
    }
}
