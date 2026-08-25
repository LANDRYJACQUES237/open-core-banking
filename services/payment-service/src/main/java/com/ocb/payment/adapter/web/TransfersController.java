package com.ocb.payment.adapter.web;

import com.ocb.payment.api.TransfersApi;
import com.ocb.payment.api.model.Transaction;
import com.ocb.payment.api.model.TransferRequest;
import com.ocb.payment.application.TransferCommand;
import com.ocb.payment.application.TransferService;
import com.ocb.platform.web.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransfersController implements TransfersApi {

    private final TransferService transferService;
    private final PaymentApiMapper mapper;

    public TransfersController(TransferService transferService, PaymentApiMapper mapper) {
        this.transferService = transferService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Transaction> requestTransfer(String idempotencyKey,
                                                       TransferRequest request) {
        TransferService.Result result = transferService.request(new TransferCommand(
                request.getExternalRef(),
                request.getAmount(),
                request.getCurrency(),
                request.getFromWalletAccountRef(),
                request.getToWalletAccountRef(),
                idempotencyKey,
                CallerIdentity.current(),
                CorrelationIdFilter.current()));

        // 201 et non 202, contrairement a un encaissement ou a un decaissement :
        // l'operation est terminee quand la reponse part. Rendre 202 laisserait croire
        // qu'il reste quelque chose a attendre.
        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(mapper.toApi(result.transaction()));
    }
}
