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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CollectionsController implements CollectionsApi {

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
                callerId(),
                CorrelationIdFilter.current()));

        // 202 et non 200 : la demande est prise en charge, elle n'est pas terminee.
        // Un paiement Mobile Money attend l'approbation du client sur son telephone ;
        // pretendre rendre un resultat obligerait a inventer une issue en cas de timeout.
        return ResponseEntity
                .status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(mapper.toApi(result.transaction()));
    }

    /**
     * Portee des cles d'idempotence : le sujet du jeton.
     *
     * <p>Deux clients qui choisissent la meme cle — ce qui arrive des qu'un client utilise
     * des compteurs plutot que des identifiants aleatoires — ne doivent pas se voler
     * mutuellement leurs reponses. Le second recevrait la transaction du premier et
     * croirait sa demande prise en charge alors qu'elle aurait ete ignoree.
     *
     * <p>L'identite vient du jeton verifie, jamais d'un en-tete fourni par l'appelant : ce
     * dernier pourrait se declarer n'importe qui et lire les transactions d'un autre
     * marchand par simple collision de cle.
     */
    private String callerId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            // La chaine de securite refuse deja les requetes non authentifiees. Si on
            // arrive ici, c'est qu'une regle a ete relachee par erreur : mieux vaut
            // echouer que d'agreger silencieusement tous les appelants sous une meme
            // portee.
            throw new IllegalStateException(
                    "Aucune identite authentifiee : la portee d'idempotence serait partagee");
        }
        return authentication.getName();
    }
}
