package com.ocb.ledger.adapter.web;

import com.ocb.ledger.api.model.Account;
import com.ocb.ledger.api.model.AccountStatus;
import com.ocb.ledger.api.model.AccountType;
import com.ocb.ledger.api.model.Balance;
import com.ocb.ledger.api.model.Direction;
import com.ocb.ledger.api.model.JournalEntry;
import com.ocb.ledger.api.model.PostingLine;
import com.ocb.ledger.api.model.StatementLine;
import com.ocb.ledger.api.model.StatementPage;
import com.ocb.ledger.domain.AccountBalance;
import com.ocb.ledger.domain.EntryLine;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.PostedEntry;
import com.ocb.ledger.domain.Statement;
import org.springframework.stereotype.Component;

/**
 * Traduction entre le domaine et les types generes depuis le contrat OpenAPI.
 *
 * <p>Cette couche parait redondante — les deux modeles se ressemblent — mais elle est ce
 * qui permet au contrat et au domaine d'evoluer independamment. Sans elle, renommer un
 * champ du domaine casserait l'API publique, et inversement une contrainte du contrat
 * remonterait jusqu'aux invariants comptables.
 *
 * <p>Les montants sortent en chaine via {@code toPlainString()}, jamais en nombre JSON :
 * un nombre JSON est parse en {@code double} par de nombreux clients, ce qui detruit la
 * precision d'un montant sans qu'aucune erreur ne soit levee.
 */
@Component
public class LedgerApiMapper {

    public Account toApi(LedgerAccount account) {
        Account api = new Account(
                account.accountNumber(),
                AccountType.fromValue(account.type().name()),
                Direction.fromValue(account.normalSide().name()),
                account.currency().getCurrencyCode(),
                AccountStatus.fromValue(account.status().name()),
                account.openedAt());
        api.setOwnerRef(account.ownerRef());
        api.setName(account.name());
        return api;
    }

    public Balance toApi(AccountBalance balance) {
        return new Balance(
                balance.accountNumber(),
                balance.balance().currencyCode(),
                balance.balance().toPlainString(),
                balance.entrySeq(),
                balance.computedAt());
    }

    public JournalEntry toApi(PostedEntry entry) {
        JournalEntry api = new JournalEntry(
                entry.entryRef(),
                entry.entrySeq(),
                entry.description(),
                entry.valueDate(),
                entry.postedAt(),
                entry.lines().stream().map(this::toApi).toList());
        api.setTransactionRef(entry.transactionRef());
        api.setReversesEntryRef(entry.reversesEntryRef());
        api.setReversedByEntryRef(entry.reversedByEntryRef());
        return api;
    }

    public PostingLine toApi(EntryLine line) {
        return new PostingLine(
                line.lineNo(),
                line.accountNumber(),
                Direction.fromValue(line.direction().name()),
                line.amount().toPlainString(),
                line.amount().currencyCode());
    }

    public StatementPage toApi(Statement statement) {
        return new StatementPage(
                statement.accountNumber(),
                statement.page(),
                statement.size(),
                statement.totalElements(),
                statement.entries().stream().map(entry -> {
                    StatementLine line = new StatementLine(
                            entry.entryRef(),
                            entry.entrySeq(),
                            entry.postedAt(),
                            entry.valueDate(),
                            entry.description(),
                            Direction.fromValue(entry.direction().name()),
                            entry.amount().toPlainString(),
                            entry.amount().currencyCode(),
                            entry.runningBalance().toPlainString());
                    line.setTransactionRef(entry.transactionRef());
                    return line;
                }).toList());
    }
}
