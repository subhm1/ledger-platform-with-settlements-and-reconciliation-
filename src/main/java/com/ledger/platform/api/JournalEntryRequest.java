package com.ledger.platform.api;

import java.util.UUID;

public record JournalEntryRequest(
        UUID accountId,
        String direction,
        Long amountMinor
) {}
