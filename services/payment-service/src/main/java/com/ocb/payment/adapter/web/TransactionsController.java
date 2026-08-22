package com.ocb.payment.adapter.web;

import com.ocb.payment.api.TransactionsApi;
import com.ocb.payment.api.model.StateTransition;
import com.ocb.payment.api.model.Transaction;
import com.ocb.payment.application.TransactionStateService;
import com.ocb.payment.domain.port.TransactionStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class TransactionsController implements TransactionsApi {

    private final TransactionStateService stateService;
    private final TransactionStore transactions;
    private final PaymentApiMapper mapper;

    public TransactionsController(TransactionStateService stateService,
                                  TransactionStore transactions,
                                  PaymentApiMapper mapper) {
        this.stateService = stateService;
        this.transactions = transactions;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Transaction> getTransaction(UUID transactionId) {
        return ResponseEntity.ok(mapper.toApi(stateService.require(transactionId)));
    }

    @Override
    public ResponseEntity<List<StateTransition>> getTransactionTransitions(UUID transactionId) {
        // Lever si la transaction n'existe pas : sans cela, un identifiant inconnu
        // retournerait une liste vide, indiscernable d'une transaction sans historique.
        stateService.require(transactionId);
        return ResponseEntity.ok(
                transactions.transitionsOf(transactionId).stream().map(mapper::toApi).toList());
    }
}
