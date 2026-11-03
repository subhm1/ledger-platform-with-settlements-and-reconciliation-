package com.ledger.platform.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class SettlementReadModels {
    private SettlementReadModels() { }

    public record Batch(
            UUID batchId,
            String sourceCode,
            String reportId,
            String fileSha256,
            String status,
            OffsetDateTime reportingPeriodStart,
            OffsetDateTime reportingPeriodEnd,
            OffsetDateTime createdAt,
            long recordCount,
            long validRecordCount,
            long invalidRecordCount) { }

    public record SettlementRecord(
            UUID recordId,
            int rowNumber,
            String externalTransactionId,
            String transactionReference,
            String currencyCode,
            Integer minorUnitExponent,
            String grossAmountMinor,
            String feeAmountMinor,
            String adjustmentAmountMinor,
            String netAmountMinor,
            String validationStatus,
            List<String> validationErrors) {
        public SettlementRecord {
            validationErrors = List.copyOf(validationErrors);
        }
    }

    public record ReconciliationRun(
            UUID runId,
            UUID batchId,
            int runNumber,
            String ruleVersion,
            String status,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) { }

    public record ReconciliationResult(
            UUID resultId,
            UUID batchId,
            UUID runId,
            UUID externalRecordId,
            UUID internalTransactionId,
            String outcome,
            String transactionReference,
            String currencyCode,
            Integer minorUnitExponent,
            String reportedGrossMinor,
            String internalDebitTotalMinor,
            String ruleVersion,
            List<String> validationErrors,
            OffsetDateTime createdAt) {
        public ReconciliationResult {
            validationErrors = List.copyOf(validationErrors);
        }
    }
}
