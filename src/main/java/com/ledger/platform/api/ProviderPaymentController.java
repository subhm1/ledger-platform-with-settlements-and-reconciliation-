package com.ledger.platform.api;

import com.ledger.platform.service.ProviderPaymentReconciliationService;
import com.ledger.platform.service.ProviderPaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/provider-payments")
public final class ProviderPaymentController {
    private final ProviderPaymentService service;
    private final ProviderPaymentReconciliationService reconciliationService;

    public ProviderPaymentController(ProviderPaymentService service,
                                     ProviderPaymentReconciliationService reconciliationService) {
        this.service = service;
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    public ResponseEntity<ProviderPaymentModels.CreateResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody ProviderPaymentModels.CreateRequest request,
            Principal principal) {
        var result = service.create(request, key, principal.getName());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.response());
    }

    @GetMapping
    public ProviderPaymentModels.AttemptPage list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{attemptId}")
    public ProviderPaymentModels.Detail detail(@PathVariable UUID attemptId) {
        return service.detail(attemptId);
    }

    @PostMapping("/{attemptId}/verify-checkout")
    public ProviderPaymentModels.Detail verify(
            @PathVariable UUID attemptId,
            @RequestBody ProviderPaymentModels.CheckoutVerification request) {
        return service.verifyCheckout(attemptId, request);
    }

    @PostMapping("/{attemptId}/recover-order")
    public ProviderPaymentModels.Detail recover(@PathVariable UUID attemptId) {
        return service.recoverOrder(attemptId);
    }

    @GetMapping("/{attemptId}/webhook-events")
    public ProviderPaymentModels.WebhookEventPage webhookEvents(
            @PathVariable UUID attemptId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return service.listWebhookEvents(attemptId, page, size);
    }

    @PostMapping("/webhook-events/{webhookEventId}/replay")
    public ProviderPaymentModels.WebhookAck replayWebhook(@PathVariable UUID webhookEventId) {
        return service.replayWebhookEvent(webhookEventId);
    }

    // ---- Provider Payment Reconciliation Endpoints ----

    @PostMapping("/reconciliation-runs")
    public ResponseEntity<ProviderPaymentReconciliationModels.RunSummary> triggerReconciliation() {
        return ResponseEntity.status(HttpStatus.CREATED).body(reconciliationService.reconcile());
    }

    @GetMapping("/reconciliation-runs")
    public ApiPage<ProviderPaymentReconciliationModels.RunSummary> listReconciliationRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reconciliationService.listRuns(page, size);
    }

    @GetMapping("/reconciliation-runs/{runId}")
    public ProviderPaymentReconciliationModels.RunSummary getReconciliationRun(
            @PathVariable UUID runId) {
        return reconciliationService.getRunSummary(runId);
    }

    @GetMapping("/reconciliation-runs/{runId}/results")
    public ApiPage<ProviderPaymentReconciliationModels.ResultItem> listReconciliationResults(
            @PathVariable UUID runId,
            @RequestParam(required = false) String outcome,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return reconciliationService.listResults(runId, outcome, page, size);
    }
}


