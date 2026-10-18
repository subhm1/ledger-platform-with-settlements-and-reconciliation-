package com.ledger.platform.api;

import java.time.Instant;
import java.util.UUID;

public record JournalTransactionResponse(
        UUID transactionId,
        String transactionReference,
        String currencyCode,
        String status,
        Instant postedAt
) {}