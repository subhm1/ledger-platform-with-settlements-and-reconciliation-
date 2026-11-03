package com.ledger.platform.api;

import java.util.UUID;

public record ReconciliationRunResponse(UUID runId, UUID batchId) {
}
