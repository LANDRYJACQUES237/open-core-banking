package com.ocb.ledger.application;

import com.ocb.ledger.domain.AccountStatus;
import com.ocb.ledger.domain.AccountType;
import com.ocb.ledger.domain.AuditEvent;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.LedgerErrors;
import com.ocb.ledger.domain.port.AccountStore;
import com.ocb.ledger.domain.port.AuditStore;
import com.ocb.platform.domain.error.ConflictException;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.error.ResourceNotFoundException;
import com.ocb.platform.domain.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountStore accounts;
    private final AuditStore audit;

    public AccountService(AccountStore accounts, AuditStore audit) {
        this.accounts = accounts;
        this.audit = audit;
    }

    @Transactional
    public Result open(String accountNumber, AccountType type, String currencyCode,
                       String ownerRef, String name, String idempotencyKey, String correlationId) {

        Currency currency = Money.currencyOf(currencyCode);
        String fingerprint = RequestFingerprint.ofAccount(
                accountNumber, type.name(), currency.getCurrencyCode(), ownerRef, name);

        // Rejeu : la cle designe deja un compte. On verifie que c'est bien la meme demande
        // avant de repondre, sinon on renverrait a l'appelant un compte qu'il n'a pas
        // demande en lui laissant croire que son ouverture a abouti.
        Optional<LedgerAccount> replay = accounts.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            requireSameRequest(replay.get(), fingerprint, idempotencyKey);
            return new Result(replay.get(), false);
        }

        if (accounts.findByNumber(accountNumber).isPresent()) {
            throw new ConflictException(
                    LedgerErrors.ACCOUNT_NUMBER_TAKEN,
                    "Le compte %s existe deja".formatted(accountNumber));
        }

        UUID parentId = resolveParent(accountNumber, type);

        LedgerAccount account = new LedgerAccount(
                UUID.randomUUID(), accountNumber, type, currency, ownerRef, name,
                AccountStatus.ACTIVE, true, parentId, OffsetDateTime.now());

        AccountStore.Opened opened = accounts.open(account, idempotencyKey);

        if (!opened.created()) {
            requireSameRequest(opened.account(), fingerprint, idempotencyKey);
            return new Result(opened.account(), false);
        }

        audit.append(AuditEvent.of("ACCOUNT_OPENED", "Account", accountNumber, correlationId,
                Map.of("accountType", type.name(), "currency", currency.getCurrencyCode())));

        return new Result(opened.account(), true);
    }

    @Transactional(readOnly = true)
    public LedgerAccount byNumber(String accountNumber) {
        return accounts.findByNumber(accountNumber).orElseThrow(() ->
                new ResourceNotFoundException(LedgerErrors.ACCOUNT_NOT_FOUND,
                        "Compte %s introuvable".formatted(accountNumber)));
    }

    /**
     * Rattache un sous-compte a son compte de regroupement.
     *
     * <p>{@code 2100.wallet-c} devient enfant de {@code 2100}. Le rattachement n'est pas
     * decoratif : il permettra d'agreger tous les portefeuilles clients pour verifier
     * que leur total correspond bien au float detenu chez les operateurs, controle de
     * coherence central d'une plateforme de monnaie electronique.
     */
    private UUID resolveParent(String accountNumber, AccountType type) {
        int dot = accountNumber.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String parentNumber = accountNumber.substring(0, dot);
        LedgerAccount parent = accounts.findByNumber(parentNumber).orElseThrow(() ->
                new ResourceNotFoundException(
                        LedgerErrors.PARENT_ACCOUNT_NOT_FOUND,
                        ("Le compte de regroupement %s n'existe pas. Un sous-compte doit se "
                                + "rattacher a un compte du plan de comptes").formatted(parentNumber)));

        if (parent.type() != type) {
            throw new InvariantViolationException(
                    LedgerErrors.ACCOUNT_NOT_POSTABLE,
                    ("Le sous-compte %s est declare %s alors que son regroupement %s est %s. "
                            + "Un portefeuille client est un compte de passif, comme son regroupement")
                            .formatted(accountNumber, type, parentNumber, parent.type()));
        }
        return parent.id();
    }

    private void requireSameRequest(LedgerAccount existing, String fingerprint, String idempotencyKey) {
        String existingFingerprint = RequestFingerprint.ofAccount(
                existing.accountNumber(), existing.type().name(),
                existing.currency().getCurrencyCode(), existing.ownerRef(), existing.name());

        if (!fingerprint.equals(existingFingerprint)) {
            throw new InvariantViolationException(
                    LedgerErrors.IDEMPOTENCY_KEY_REUSED,
                    ("La cle d'idempotence %s a deja servi a ouvrir le compte %s, dont les "
                            + "caracteristiques different de celles demandees")
                            .formatted(idempotencyKey, existing.accountNumber()));
        }
    }

    public record Result(LedgerAccount account, boolean created) {
    }
}
