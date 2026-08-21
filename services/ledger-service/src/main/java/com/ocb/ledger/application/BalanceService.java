package com.ocb.ledger.application;

import com.ocb.ledger.domain.AccountBalance;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.Statement;
import com.ocb.ledger.domain.StatementEntry;
import com.ocb.ledger.domain.port.BalanceStore;
import com.ocb.platform.domain.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class BalanceService {

    private final AccountService accountService;
    private final BalanceStore balances;

    public BalanceService(AccountService accountService, BalanceStore balances) {
        this.accountService = accountService;
        this.balances = balances;
    }

    @Transactional(readOnly = true)
    public AccountBalance balanceOf(String accountNumber) {
        LedgerAccount account = accountService.byNumber(accountNumber);
        BalanceStore.RawBalance raw = balances.rawBalanceOf(account.id());
        return AccountBalance.fromRaw(account, raw.raw(), raw.entrySeq(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public Statement statementOf(String accountNumber, int page, int size) {
        LedgerAccount account = accountService.byNumber(accountNumber);

        List<StatementEntry> entries = balances.statement(account.id(), page, size).stream()
                .map(row -> new StatementEntry(
                        row.entryRef(),
                        row.entrySeq(),
                        row.postedAt(),
                        row.valueDate(),
                        row.description(),
                        row.transactionRef(),
                        row.direction(),
                        Money.of(row.amount(), row.currency()),
                        // Le solde progressif remonte au sens debiteur : il subit la meme
                        // conversion que le solde courant, sinon un releve de portefeuille
                        // afficherait des soldes negatifs sur chaque ligne.
                        Money.of(account.type().fromRawDebitBalance(row.runningRaw()), account.currency())))
                .toList();

        return new Statement(accountNumber, page, size, balances.statementSize(account.id()), entries);
    }
}
