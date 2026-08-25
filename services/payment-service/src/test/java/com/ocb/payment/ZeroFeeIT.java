package com.ocb.payment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Un bareme de frais a zero ne doit rien casser.
 *
 * <p><b>Le piege.</b> Le grand livre refuse toute ligne de montant nul — une ligne qui ne
 * deplace rien masque generalement un bug de calcul, et la regle est doublee d'une
 * contrainte SQL. Une ecriture qui inclurait systematiquement sa ligne de commission
 * deviendrait donc invalide des que la commission vaut zero.
 *
 * <p>Or zero est une configuration parfaitement legitime : une promotion, une offre sans
 * frais, un client interne. Le defaut ne se manifesterait pas au demarrage mais a la
 * premiere operation, sous la forme d'un refus comptable sans rapport apparent avec le
 * fichier de configuration qu'on vient de modifier.
 *
 * <p>Ce test fixe les deux baremes a zero, ce qui lui vaut son propre contexte applicatif.
 * C'est le prix a payer pour eprouver une valeur de configuration plutot que de la
 * supposer inoffensive.
 */
@TestPropertySource(properties = {
        "ocb.fees.transfer.basis-points=0",
        "ocb.fees.transfer.fixed=0",
        "ocb.fees.disbursement.basis-points=0",
        "ocb.fees.disbursement.fixed=0"
})
class ZeroFeeIT extends StubbedLedgerTestBase {

    private static final String FUNDS = "10000";

    private String destination;

    @BeforeEach
    void fundTheSender() {
        stub.credit(wallet, FUNDS);
        destination = "2100.wallet-dest-" + suffix;
        stub.credit(destination, "0");
    }

    @Test
    @DisplayName("un transfert sans frais passe, et ne comporte que deux lignes")
    void feelessTransferPosts() {
        ApiResponse response = post("/v1/transfers", "zero-tr-" + suffix, """
                {
                  "externalRef": "TX-%s",
                  "amount": "2000",
                  "currency": "XAF",
                  "fromWalletAccountRef": "%s",
                  "toWalletAccountRef": "%s"
                }
                """.formatted(suffix, wallet, destination));

        assertThat(response.status()).isEqualTo(201);

        assertThat(stub.balanceOfWallet(wallet).amount())
                .as("sans frais, l'emetteur ne paie que le montant")
                .isEqualByComparingTo(new BigDecimal("8000"));
        assertThat(stub.balanceOfWallet(destination).amount())
                .isEqualByComparingTo(new BigDecimal("2000"));

        assertThat(linesOfLastEntry())
                .as("la ligne de commission a disparu au lieu de valoir zero")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("un decaissement sans frais engage les fonds normalement")
    void feelessDisbursementReserves() {
        ApiResponse response = post("/v1/disbursements", "zero-disb-" + suffix,
                disbursementBody("5000", wallet));

        assertThat(response.status()).isEqualTo(202);
        assertThat(stub.balanceOfWallet(wallet).amount())
                .isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(linesOfLastEntry()).isEqualTo(2);
    }

    private int linesOfLastEntry() {
        var entries = stub.postedEntries();
        return entries.get(entries.size() - 1).lines().size();
    }
}
