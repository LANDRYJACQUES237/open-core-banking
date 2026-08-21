package com.ocb.ledger.domain;

import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.money.Money;

import java.util.Objects;

/**
 * Une ligne d'ecriture : un compte, un sens, un montant strictement positif.
 *
 * <p>Le montant est un {@link Money}, donc adosse a une devise et a une echelle
 * imposee par cette devise. Un montant nul est refuse : une ligne qui ne deplace
 * rien n'a pas de sens comptable et masque generalement un bug de calcul en amont.
 */
public record EntryLine(int lineNo, String accountNumber, Direction direction, Money amount) {

    public EntryLine {
        Objects.requireNonNull(accountNumber, "accountNumber");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");

        if (!amount.isPositive()) {
            throw new InvariantViolationException(
                    LedgerErrors.UNBALANCED_ENTRY,
                    ("Le montant d'une ligne doit etre strictement positif ; recu %s sur le compte %s. "
                            + "Le sens est porte par la direction, pas par le signe du montant")
                            .formatted(amount, accountNumber));
        }
    }

    public EntryLine withLineNo(int newLineNo) {
        return new EntryLine(newLineNo, accountNumber, direction, amount);
    }

    /** Ligne inverse, utilisee pour construire une contre-passation. */
    public EntryLine reversed() {
        return new EntryLine(lineNo, accountNumber, direction.opposite(), amount);
    }

    public boolean isDebit() {
        return direction == Direction.DR;
    }
}
