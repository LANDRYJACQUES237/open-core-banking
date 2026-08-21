package com.ocb.ledger.domain.port;

import com.ocb.ledger.domain.Direction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Port de calcul des soldes et des releves.
 *
 * <p>Toutes les valeurs echangees ici sont brutes, exprimees au sens debiteur
 * (positives au debit, negatives au credit). La conversion vers le sens normal du
 * compte appartient au domaine : c'est une regle comptable, elle n'a pas a etre
 * dupliquee dans du SQL ou elle serait invisible aux tests unitaires.
 */
public interface BalanceStore {

    /** Somme brute des ecritures d'un compte, et numero de la derniere ecriture prise en compte. */
    RawBalance rawBalanceOf(UUID accountId);

    List<StatementRow> statement(UUID accountId, int page, int size);

    long statementSize(UUID accountId);

    // --- Instantanes -----------------------------------------------------------------

    /**
     * Met a jour les instantanes de solde de maniere incrementale.
     *
     * @return le nombre de comptes rafraichis
     */
    int refreshSnapshots();

    /**
     * Recalcule chaque solde depuis le debut et le compare a l'instantane correspondant.
     *
     * <p>Existe parce qu'un cache silencieusement faux est pire que pas de cache : cette
     * methode transforme une derive possible en condition detectable, exposable en metrique.
     *
     * @return les ecarts constates, vide si tout concorde
     */
    List<SnapshotDiscrepancy> verifySnapshots();

    void clearSnapshots();

    /** Somme de toutes les ecritures du grand livre. Doit toujours valoir zero. */
    BigDecimal globalImbalance();

    record RawBalance(BigDecimal raw, long entrySeq) {
    }

    record StatementRow(String entryRef,
                        long entrySeq,
                        OffsetDateTime postedAt,
                        LocalDate valueDate,
                        String description,
                        String transactionRef,
                        Direction direction,
                        BigDecimal amount,
                        String currency,
                        BigDecimal runningRaw) {
    }

    record SnapshotDiscrepancy(UUID accountId,
                               String accountNumber,
                               BigDecimal recomputed,
                               BigDecimal snapshotted) {
    }
}
