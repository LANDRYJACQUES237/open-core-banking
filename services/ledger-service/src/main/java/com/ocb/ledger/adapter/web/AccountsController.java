package com.ocb.ledger.adapter.web;

import com.ocb.ledger.api.AccountsApi;
import com.ocb.ledger.api.model.Account;
import com.ocb.ledger.api.model.Balance;
import com.ocb.ledger.api.model.OpenAccountRequest;
import com.ocb.ledger.api.model.StatementPage;
import com.ocb.ledger.application.AccountService;
import com.ocb.ledger.application.BalanceService;
import com.ocb.ledger.domain.AccountType;
import com.ocb.platform.web.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountsController implements AccountsApi {

    private final AccountService accountService;
    private final BalanceService balanceService;
    private final LedgerApiMapper mapper;

    public AccountsController(AccountService accountService,
                              BalanceService balanceService,
                              LedgerApiMapper mapper) {
        this.accountService = accountService;
        this.balanceService = balanceService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Account> openAccount(String idempotencyKey, OpenAccountRequest request) {
        AccountService.Result result = accountService.open(
                request.getAccountNumber(),
                AccountType.valueOf(request.getAccountType().getValue()),
                request.getCurrency(),
                request.getOwnerRef(),
                request.getName(),
                idempotencyKey,
                CorrelationIdFilter.current());

        // 201 a la creation, 200 sur rejeu. La distinction n'est pas cosmetique : elle
        // dit a l'appelant si son appel a produit un effet ou s'il a retrouve un effet
        // deja produit, information qu'un 201 systematique lui cacherait.
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(mapper.toApi(result.account()));
    }

    @Override
    public ResponseEntity<Account> getAccount(String accountNumber) {
        return ResponseEntity.ok(mapper.toApi(accountService.byNumber(accountNumber)));
    }

    @Override
    public ResponseEntity<Balance> getAccountBalance(String accountNumber) {
        return ResponseEntity.ok(mapper.toApi(balanceService.balanceOf(accountNumber)));
    }

    @Override
    public ResponseEntity<StatementPage> getAccountStatement(String accountNumber, Integer page, Integer size) {
        return ResponseEntity.ok(mapper.toApi(balanceService.statementOf(accountNumber, page, size)));
    }
}
