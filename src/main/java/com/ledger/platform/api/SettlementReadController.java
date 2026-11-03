package com.ledger.platform.api;

import com.ledger.platform.service.SettlementReadService;
import com.ledger.platform.service.ReconciliationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public final class SettlementReadController {
    private final SettlementReadService readService;
    private final ReconciliationService reconciliationService;

    public SettlementReadController(
            SettlementReadService readService, ReconciliationService reconciliationService) {
        this.readService = readService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/settlement-batches")
    public ApiPage<SettlementReadModels.Batch> batches(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sourceCode,
            @RequestParam(required = false) String reportId) {
        return readService.batches(sourceCode, reportId, page, size);
    }

    @GetMapping("/settlement-batches/{batchId}/records")
    public ApiPage<SettlementReadModels.SettlementRecord> records(
            @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String validationStatus) {
        return readService.records(batchId, validationStatus, page, size);
    }

    @GetMapping("/settlement-batches/{batchId}/reconciliation-runs")
    public ApiPage<SettlementReadModels.ReconciliationRun> runs(
            @PathVariable UUID batchId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return readService.runs(batchId, page, size);
    }

    @org.springframework.web.bind.annotation.PostMapping("/settlement-batches/{batchId}/reconciliation-runs")
    public ResponseEntity<ReconciliationRunResponse> reconcile(@PathVariable UUID batchId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ReconciliationRunResponse(reconciliationService.reconcile(batchId), batchId));
    }

    @GetMapping("/reconciliation-runs/{runId}/results")
    public ApiPage<SettlementReadModels.ReconciliationResult> results(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String outcome) {
        return readService.results(runId, outcome, page, size);
    }
}
