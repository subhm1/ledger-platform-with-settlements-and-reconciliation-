package com.ledger.platform.api;

import com.ledger.platform.service.JournalPostingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/journal-transactions")
public class JournalTransactionController {

    private final JournalPostingService postingService;

    public JournalTransactionController(JournalPostingService postingService) {
        this.postingService = postingService;
    }

    @PostMapping
    public ResponseEntity<JournalTransactionResponse> post(
            @RequestBody PostJournalTransactionRequest request) {
        return ResponseEntity.ok(postingService.post(request));
    }
}