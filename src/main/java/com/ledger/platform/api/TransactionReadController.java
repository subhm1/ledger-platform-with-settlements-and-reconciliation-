package com.ledger.platform.api;

import com.ledger.platform.service.TransactionReadService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public final class TransactionReadController {
    private final TransactionReadService readService;

    public TransactionReadController(TransactionReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    public ApiPage<TransactionReadModels.Summary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String transactionReference,
            @RequestParam(required = false) String currencyCode) {
        return readService.list(transactionReference, currencyCode, page, size);
    }

    @GetMapping("/{transactionId}")
    public TransactionReadModels.Detail get(@PathVariable UUID transactionId) {
        return readService.get(transactionId);
    }
}
