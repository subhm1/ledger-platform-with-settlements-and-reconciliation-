package com.ledger.platform.api;

import java.util.List;

public record PostJournalTransactionRequest(
        String scope,
        String idempotencyKey,
        String transactionReference,
        String currencyCode,
        String description,
        List<JournalEntryRequest> entries
) {}