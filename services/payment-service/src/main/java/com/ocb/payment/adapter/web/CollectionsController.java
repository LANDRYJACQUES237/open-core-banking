package com.ocb.payment.adapter.web;

import com.ocb.payment.api.CollectionsApi;
import com.ocb.payment.api.model.CollectionRequest;
import com.ocb.payment.api.model.Transaction;
import com.ocb.payment.application.CollectionCommand;
import com.ocb.payment.application.CollectionService;
import com.ocb.payment.domain.ProviderCode;
import com.ocb.platform.web.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectionsController implements CollectionsApi {

    /**
     * Portee des cles d'idempotence.
     *
     * <p>En Phase 3, ce sera le sujet du JWT : deux clients qui choisissent la meme cle —
     * ce qui arrive des qu'un client utilise des compteurs plutot que des UUID — ne
     * doivent pas se voler mutuellement leurs reponses. Tant que le service n'est pas
     * authentifie, une portee unique est la seule valeur honnete ; l'inventer a partir
     * d'un en-tete non verifie donnerait une fausse impression d'isolation.
     */
    private static final String ANONYMOUS_SCOPE = "anonymous";

    private final CollectionService collectionService;
    private final PaymentApiMapper mapper;

    public CollectionsController(CollectionService collectionService, PaymentApiMapper mapper) {
        this.collectionService = collectionService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<Transaction> requestCollection(String idempotencyKey,
                                                         CollectionRequest request) {
        CollectionService.Result result = collectionService.request(new CollectionCommand(
                request.getExternalRef(),
                request.getAmount(),
                request.getCurrency(),
                request.getPayerMsisdn(),
                request.getWalletAccountRef(),
                ProviderCode.valueOf(request.getProviderCode().getValue()),
                idempotencyKey,
                ANONYMOUS_SCOPE,
                CorrelationIdFilter.current()));

        // 202 et non 200 : la demande est prise en charge, elle n'est pas terminee.
        // Un paiement Mobile Money attend l'approbation du client sur son telephone ;
        // pretendre rendre un resultat obligerait a inventer une issue en cas de timeout.
        return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(mapper.toApi(result.transaction()));
    }
}
