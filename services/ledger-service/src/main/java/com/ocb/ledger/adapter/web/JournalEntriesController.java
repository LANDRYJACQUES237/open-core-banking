package com.ocb.ledger.adapter.web;

import com.ocb.ledger.api.JournalEntriesApi;
import com.ocb.ledger.api.model.JournalEntry;
import com.ocb.ledger.api.model.PostJournalEntryRequest;
import com.ocb.ledger.api.model.ReversalRequest;
import com.ocb.ledger.application.JournalEntryService;
import com.ocb.ledger.application.PostEntryCommand;
import com.ocb.ledger.domain.Direction;
import com.ocb.platform.web.CorrelationIdFilter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class JournalEntriesController implements JournalEntriesApi {

    private final JournalEntryService journalEntryService;
    private final LedgerApiMapper mapper;

    public JournalEntriesController(JournalEntryService journalEntryService, LedgerApiMapper mapper) {
        this.journalEntryService = journalEntryService;
        this.mapper = mapper;
    }

    @Override
    public ResponseEntity<JournalEntry> postJournalEntry(String idempotencyKey,
                                                         PostJournalEntryRequest request) {
        List<PostEntryCommand.Line> lines = request.getLines().stream()
                .map(line -> new PostEntryCommand.Line(
                        line.getAccountNumber(),
                        Direction.valueOf(line.getDirection().getValue()),
                        line.getAmount(),
                        line.getCurrency()))
                .toList();

        JournalEntryService.Result result = journalEntryService.post(new PostEntryCommand(
                request.getEntryRef(),
                request.getTransactionRef(),
                request.getDescription(),
                request.getValueDate(),
                lines,
                idempotencyKey,
                CorrelationIdFilter.current()));

        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(mapper.toApi(result.entry()));
    }

    @Override
    public ResponseEntity<JournalEntry> getJournalEntry(String entryRef) {
        return ResponseEntity.ok(mapper.toApi(journalEntryService.byRef(entryRef)));
    }

    @Override
    public ResponseEntity<JournalEntry> reverseJournalEntry(String entryRef,
                                                            String idempotencyKey,
                                                            ReversalRequest request) {
        JournalEntryService.Result result = journalEntryService.reverse(
                entryRef,
                request.getReason(),
                request.getEntryRef(),
                idempotencyKey,
                CorrelationIdFilter.current());

        return ResponseEntity
                .status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(mapper.toApi(result.entry()));
    }
}
