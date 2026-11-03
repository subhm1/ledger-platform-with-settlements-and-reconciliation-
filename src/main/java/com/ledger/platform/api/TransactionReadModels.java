package com.ledger.platform.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransactionReadModels {
    private TransactionReadModels() { }

    public record Summary(
            UUID transactionId,
            String transactionReference,
            String currencyCode,
            int minorUnitExponent,
            String totalDebitMinor,
            String status,
            Instant postedAt,
            UUID reversalOfTransactionId) { }

    public record Detail(
            UUID transactionId,
            String transactionReference,
            String currencyCode,
            int minorUnitExponent,
            String description,
            String status,
            Instant postedAt,
            UUID reversalOfTransactionId,
            List<Entry> entries) {
        public Detail {
            entries = List.copyOf(entries);
        }
    }

    public record Entry(
            UUID entryId,
            UUID accountId,
            String accountCode,
            String accountDisplayName,
            String accountRole,
            String direction,
            String amountMinor,
            String currencyCode,
            int minorUnitExponent) { }
}
