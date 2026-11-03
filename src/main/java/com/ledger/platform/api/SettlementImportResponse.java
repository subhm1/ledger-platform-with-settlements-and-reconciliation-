package com.ledger.platform.api;

import java.util.UUID;

public record SettlementImportResponse(
        UUID batchId,
        String sourceCode,
        String reportId,
        String fileSha256,
        String status
) {
}
