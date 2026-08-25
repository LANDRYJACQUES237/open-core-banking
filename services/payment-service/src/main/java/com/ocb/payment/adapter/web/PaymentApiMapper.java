package com.ocb.payment.adapter.web;

import com.ocb.payment.api.model.ProviderCode;
import com.ocb.payment.api.model.StateTransition;
import com.ocb.payment.api.model.Transaction;
import com.ocb.payment.api.model.TransactionStatus;
import com.ocb.payment.api.model.TransactionType;
import com.ocb.payment.domain.PaymentTransaction;
import com.ocb.payment.domain.StateTransitionRecord;
import org.springframework.stereotype.Component;

@Component
public class PaymentApiMapper {

    public Transaction toApi(PaymentTransaction t) {
        Transaction api = new Transaction(
                t.id(),
                t.externalRef(),
                TransactionType.fromValue(t.type().name()),
                TransactionStatus.fromValue(t.status().name()),
                t.amount().toPlainString(),
                t.amount().currencyCode(),
                t.platformFee().toPlainString(),
                t.walletAccountRef(),
                t.createdAt(),
                t.updatedAt());
        // Absent d'un transfert, qui ne passe par aucun operateur. Le champ a quitte les
        // proprietes requises du contrat pour cette raison : y mettre une valeur par
        // defaut ferait croire a un operateur qui n'existe pas.
        api.setProviderCode(t.providerCode() == null
                ? null
                : ProviderCode.fromValue(t.providerCode().name()));
        api.setProviderFee(t.providerFee() == null ? null : t.providerFee().toPlainString());
        api.setMaskedMsisdn(t.maskedMsisdn());
        api.setProviderRef(t.providerRef());
        api.setLedgerEntryRef(t.ledgerEntryRef());
        api.setFailureCode(t.failureCode());
        return api;
    }

    public StateTransition toApi(StateTransitionRecord record) {
        StateTransition api = new StateTransition(
                record.toStatus().name(),
                record.triggerEvent(),
                record.accepted(),
                record.occurredAt());
        api.setFromStatus(record.fromStatus() == null ? null : record.fromStatus().name());
        api.setRejectionReason(record.rejectionReason());
        return api;
    }
}
