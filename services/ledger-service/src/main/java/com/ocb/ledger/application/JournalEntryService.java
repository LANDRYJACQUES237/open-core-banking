package com.ocb.ledger.application;

import com.ocb.ledger.domain.AuditEvent;
import com.ocb.ledger.domain.EntryLine;
import com.ocb.ledger.domain.JournalEntryDraft;
import com.ocb.ledger.domain.LedgerAccount;
import com.ocb.ledger.domain.LedgerErrors;
import com.ocb.ledger.domain.PostedEntry;
import com.ocb.ledger.domain.port.AccountStore;
import com.ocb.ledger.domain.port.AuditStore;
import com.ocb.ledger.domain.port.JournalStore;
import com.ocb.platform.domain.error.InvariantViolationException;
import com.ocb.platform.domain.error.ResourceNotFoundException;
import com.ocb.platform.domain.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class JournalEntryService {

    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AccountStore accounts;
    private final JournalStore journal;
    private final AuditStore audit;

    public JournalEntryService(AccountStore accounts, JournalStore journal, AuditStore audit) {
        this.accounts = accounts;
        this.journal = journal;
        this.audit = audit;
    }

    /**
     * Enregistre une ecriture.
     *
     * <p>Ordre des operations, chaque etape ayant une raison d'etre avant la suivante :
     *
     * <ol>
     *   <li>les montants sont convertis en {@link Money}, ce qui rejette immediatement une
     *       echelle incompatible avec la devise ;
     *   <li>l'empreinte est calculee sur ces valeurs normalisees, avant application des
     *       defauts, pour qu'un rejeu produise exactement la meme empreinte ;
     *   <li>le brouillon est construit, ce qui verifie l'equilibre — un objet
     *       {@link JournalEntryDraft} qui existe est necessairement equilibre ;
     *   <li>les comptes sont resolus et valides ;
     *   <li>l'ecriture est enregistree, la base opposant ses propres garde-fous.
     * </ol>
     */
    @Transactional
    public Result post(PostEntryCommand command) {
        List<EntryLine> lines = toLines(command.lines());

        String fingerprint = RequestFingerprint.ofEntry(
                command.entryRef(), command.transactionRef(), command.description(),
                command.valueDate(), lines);

        JournalEntryDraft draft = new JournalEntryDraft(
                command.entryRef() != null ? command.entryRef() : generateEntryRef(),
                command.transactionRef(),
                command.description(),
                command.valueDate() != null ? command.valueDate() : LocalDate.now(),
                lines);

        Map<String, LedgerAccount> resolved = resolveAndValidate(draft);

        JournalStore.Posted posted = journal.post(
                draft, resolved, command.idempotencyKey(), fingerprint, null, command.correlationId());

        return finish(posted, fingerprint, command.idempotencyKey(), command.correlationId(), "ENTRY_POSTED");
    }

    /**
     * Contre-passe une ecriture.
     *
     * <p>L'originale n'est ni modifiee ni supprimee : une nouvelle ecriture inverse ses
     * lignes et la designe via {@code reverses_entry_id}. L'unicite de cette colonne fait
     * le reste — une ecriture ne peut etre contre-passee qu'une fois, meme si la
     * compensation d'une saga est rejouee en parallele.
     */
    @Transactional
    public Result reverse(String entryRef, String reason, String newEntryRef,
                          String idempotencyKey, String correlationId) {

        PostedEntry original = journal.findByRef(entryRef).orElseThrow(() ->
                new ResourceNotFoundException(LedgerErrors.ENTRY_NOT_FOUND,
                        "Ecriture %s introuvable".formatted(entryRef)));

        String fingerprint = RequestFingerprint.ofReversal(entryRef, reason, newEntryRef);

        // Rejeu de la meme compensation : on rend la contre-passation deja enregistree.
        // Le controle definitif reste la contrainte d'unicite ; celui-ci evite seulement
        // de lever une erreur la ou l'appelant a un comportement parfaitement legitime.
        var replay = journal.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            requireSameFingerprint(replay.get(), fingerprint, idempotencyKey);
            return new Result(replay.get(), false);
        }

        if (original.isReversal()) {
            throw new InvariantViolationException(
                    LedgerErrors.CANNOT_REVERSE_REVERSAL,
                    ("L'ecriture %s est elle-meme une contre-passation. Contre-passer une "
                            + "contre-passation produirait une chaine d'annulations illisible ; "
                            + "enregistrez une nouvelle ecriture explicite").formatted(entryRef));
        }
        if (original.isReversed()) {
            throw new InvariantViolationException(
                    LedgerErrors.ALREADY_REVERSED,
                    "L'ecriture %s a deja ete contre-passee par %s"
                            .formatted(entryRef, original.reversedByEntryRef()));
        }

        JournalEntryDraft reversalDraft = toDraft(original)
                .reversal(newEntryRef != null ? newEntryRef : generateEntryRef(), reason);

        Map<String, LedgerAccount> resolved = resolveAndValidate(reversalDraft);

        JournalStore.Posted posted = journal.post(
                reversalDraft, resolved, idempotencyKey, fingerprint, original.id(), correlationId);

        return finish(posted, fingerprint, idempotencyKey, correlationId, "ENTRY_REVERSED");
    }

    @Transactional(readOnly = true)
    public PostedEntry byRef(String entryRef) {
        return journal.findByRef(entryRef).orElseThrow(() ->
                new ResourceNotFoundException(LedgerErrors.ENTRY_NOT_FOUND,
                        "Ecriture %s introuvable".formatted(entryRef)));
    }

    // ---------------------------------------------------------------------------------

    private Result finish(JournalStore.Posted posted, String fingerprint,
                          String idempotencyKey, String correlationId, String action) {
        if (!posted.created()) {
            requireSameFingerprint(posted.entry(), fingerprint, idempotencyKey);
            return new Result(posted.entry(), false);
        }
        audit.append(AuditEvent.of(action, "JournalEntry", posted.entry().entryRef(), correlationId,
                Map.of("entrySeq", posted.entry().entrySeq(),
                        "lineCount", posted.entry().lines().size())));
        return new Result(posted.entry(), true);
    }

    private void requireSameFingerprint(PostedEntry existing, String fingerprint, String idempotencyKey) {
        if (!fingerprint.equals(existing.requestFingerprint())) {
            throw new InvariantViolationException(
                    LedgerErrors.IDEMPOTENCY_KEY_REUSED,
                    ("La cle d'idempotence %s a deja servi pour une operation differente "
                            + "(ecriture %s). Ce n'est pas un rejeu : utilisez une cle distincte "
                            + "pour une operation distincte")
                            .formatted(idempotencyKey, existing.entryRef()));
        }
    }

    private List<EntryLine> toLines(List<PostEntryCommand.Line> commandLines) {
        List<EntryLine> lines = new java.util.ArrayList<>(commandLines.size());
        int lineNo = 1;
        for (PostEntryCommand.Line line : commandLines) {
            lines.add(new EntryLine(
                    lineNo++,
                    line.accountNumber(),
                    line.direction(),
                    Money.parse(line.amount(), line.currency())));
        }
        return lines;
    }

    private JournalEntryDraft toDraft(PostedEntry entry) {
        return new JournalEntryDraft(entry.entryRef(), entry.transactionRef(),
                entry.description(), entry.valueDate(), entry.lines());
    }

    /**
     * Resout les comptes vises et applique les regles qui dependent de leur etat.
     *
     * <p>Ces controles ne peuvent pas vivre dans {@link JournalEntryDraft} : celui-ci ne
     * connait que des numeros de compte, pas leur existence ni leur statut. Ils ne doivent
     * pas non plus vivre en SQL, ou ils echapperaient aux tests unitaires.
     */
    private Map<String, LedgerAccount> resolveAndValidate(JournalEntryDraft draft) {
        Set<String> numbers = new LinkedHashSet<>(draft.lines().stream()
                .map(EntryLine::accountNumber).toList());

        Map<String, LedgerAccount> resolved = accounts.findByNumbers(numbers);

        for (String number : numbers) {
            LedgerAccount account = resolved.get(number);
            if (account == null) {
                throw new ResourceNotFoundException(
                        LedgerErrors.ACCOUNT_NOT_FOUND,
                        "Compte %s introuvable".formatted(number));
            }
            account.requireAcceptsPostings();
            account.requireCurrency(draft.currency());
        }
        return resolved;
    }

    private static String generateEntryRef() {
        return "JE-%s-%s".formatted(
                LocalDate.now().format(REF_DATE),
                UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT));
    }

    /** Ecriture enregistree, et si elle vient d'etre creee ou si la requete etait un rejeu. */
    public record Result(PostedEntry entry, boolean created) {
    }
}
