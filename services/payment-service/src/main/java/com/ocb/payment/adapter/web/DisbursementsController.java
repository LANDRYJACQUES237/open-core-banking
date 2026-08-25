package com.ocb.payment.adapter.web;

import com.ocb.payment.api.DisbursementsApi;
import com.ocb.payment.api.model.DisbursementRequest;
import com.ocb.payment.api.model.Transaction;
import com.ocb.payment.application.DisbursementCommand;
import com.ocb.payment.application.DisbursementService;
import com.ocb.payment.domain.ProviderCode;
import com.ocb.platform.web.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DisbursementsController implements DisbursementsApi {

    private final DisbursementService disbursementService;
    private final PaymentApiMapper mapper;

    public DisbursementsController(DisbursementService disbursementService, PaymentApiMapper mapper) {
        this.disbursementService = disbursementService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Transaction> requestDisbursement(String idempotencyKey,
                                                           DisbursementRequest request) {
        DisbursementService.Result result = disbursementService.request(new DisbursementCommand(
                request.getExternalRef(),
                request.getAmount(),
                request.getCurrency(),
                request.getPayeeMsisdn(),
                request.getWalletAccountRef(),
                ProviderCode.valueOf(request.getProviderCode().getValue()),
                idempotencyKey,
                CallerIdentity.current(),
                CorrelationIdFilter.current()));

        // 202 et non 200 : les fonds sont engages, l'ordre est parti, mais l'operateur n'a
        // encore rien livre. Rendre 200 laisserait croire que le beneficiaire est paye.
        return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(mapper.toApi(result.transaction()));
    }
}
