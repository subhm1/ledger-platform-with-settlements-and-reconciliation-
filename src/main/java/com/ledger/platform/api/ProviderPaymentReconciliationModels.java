package com.ledger.platform.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProviderPaymentReconciliationModels {
    private ProviderPaymentReconciliationModels() { }

    public record RunSummary(
            UUID id,
            int runNumber,
            String ruleVersion,
            String status,
            int totalInternalRecords,
            int totalProviderRecords,
            int matchedCount,
            int unmatchedInternalCount,
            int unmatchedProviderCount,
            int duplicateCount,
            int conflictingCount,
            Instant startedAt,
            Instant completedAt) { }

    public record ResultItem(
            UUID id,
            UUID runId,
            UUID attemptId,
            UUID webhookEventId,
            String providerPaymentId,
            String providerOrderId,
            String outcome,
            String discrepancyReason,
            String detailsJson,
            Instant createdAt) { }

    public record RunDetail(
            RunSummary summary,
            List<ResultItem> results) {
        public RunDetail { results = List.copyOf(results); }
    }
}
