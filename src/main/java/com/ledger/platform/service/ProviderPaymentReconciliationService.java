package com.ledger.platform.service;

import com.ledger.platform.api.ApiPage;
import com.ledger.platform.api.ProviderPaymentReconciliationModels;
import com.ledger.platform.api.ReadApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
public final class ProviderPaymentReconciliationService {
    private static final String RULE_VERSION = "provider-payment-v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public ProviderPaymentReconciliationService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public ProviderPaymentReconciliationModels.RunSummary reconcile() {
        return transaction.execute(status -> {
            Integer nextRunNumber = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(run_number), 0) + 1 FROM public.provider_payment_reconciliation_runs",
                    Integer.class);
            int runNumber = nextRunNumber == null ? 1 : nextRunNumber;
            UUID runId = UUID.randomUUID();
            Instant startedAt = Instant.now();

            jdbc.update("""
                    INSERT INTO public.provider_payment_reconciliation_runs
                        (id, run_number, rule_version, status, started_at)
                    VALUES (?, ?, ?, 'RUNNING', CURRENT_TIMESTAMP)
                    """, runId, runNumber, RULE_VERSION);

            // Fetch internal attempts that have reached provider interaction
            List<AttemptRecord> attempts = jdbc.query("""
                    SELECT id, receipt, amount_minor, currency_code, state, provider_order_id, provider_payment_id
                    FROM public.payment_attempts
                    WHERE provider_payment_id IS NOT NULL OR state = 'CAPTURED_VERIFIED'
                    ORDER BY created_at ASC
                    """, (rs, rowNum) -> new AttemptRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("receipt"),
                    rs.getLong("amount_minor"),
                    rs.getString("currency_code"),
                    rs.getString("state"),
                    rs.getString("provider_order_id"),
                    rs.getString("provider_payment_id")));

            // Fetch provider webhook capture events that have been durably processed
            List<WebhookCaptureRecord> webhooks = jdbc.query("""
                    SELECT id, provider_event_id, provider_order_id, provider_payment_id,
                           amount_minor, currency_code, processing_state, safe_payload_json
                    FROM public.payment_webhook_events
                    WHERE event_type = 'payment.captured' AND processing_state = 'PROCESSED'
                    ORDER BY received_at ASC
                    """, (rs, rowNum) -> new WebhookCaptureRecord(
                    rs.getObject("id", UUID.class),
                    rs.getString("provider_event_id"),
                    rs.getString("provider_order_id"),
                    rs.getString("provider_payment_id"),
                    rs.getObject("amount_minor", Long.class),
                    rs.getString("currency_code"),
                    rs.getString("processing_state"),
                    rs.getString("safe_payload_json")));

            // Index webhooks by provider_payment_id
            Map<String, List<WebhookCaptureRecord>> webhooksByPaymentId = new LinkedHashMap<>();
            for (WebhookCaptureRecord wh : webhooks) {
                if (wh.providerPaymentId() != null && !wh.providerPaymentId().isBlank()) {
                    webhooksByPaymentId.computeIfAbsent(wh.providerPaymentId(), k -> new ArrayList<>()).add(wh);
                }
            }

            // Index attempts by provider_payment_id
            Map<String, List<AttemptRecord>> attemptsByPaymentId = new LinkedHashMap<>();
            for (AttemptRecord att : attempts) {
                if (att.providerPaymentId() != null && !att.providerPaymentId().isBlank()) {
                    attemptsByPaymentId.computeIfAbsent(att.providerPaymentId(), k -> new ArrayList<>()).add(att);
                }
            }

            Set<UUID> matchedWebhookIds = new HashSet<>();
            int matchedCount = 0;
            int unmatchedInternalCount = 0;
            int unmatchedProviderCount = 0;
            int duplicateCount = 0;
            int conflictingCount = 0;

            // 1. Reconcile from internal attempts perspective
            for (AttemptRecord att : attempts) {
                String paymentId = att.providerPaymentId();
                List<AttemptRecord> duplicateAttempts = paymentId == null ? List.of(att) : attemptsByPaymentId.getOrDefault(paymentId, List.of(att));
                List<WebhookCaptureRecord> matchingWebhooks = paymentId == null ? List.of() : webhooksByPaymentId.getOrDefault(paymentId, List.of());

                if (duplicateAttempts.size() > 1 || matchingWebhooks.size() > 1) {
                    duplicateCount++;
                    WebhookCaptureRecord primaryWh = matchingWebhooks.isEmpty() ? null : matchingWebhooks.getFirst();
                    if (primaryWh != null) matchedWebhookIds.add(primaryWh.id());
                    insertResult(runId, att.id(), primaryWh == null ? null : primaryWh.id(),
                            paymentId, att.providerOrderId(), "DUPLICATE",
                            "Multiple records found for provider payment ID: " + paymentId,
                            Map.of("attempt_count", duplicateAttempts.size(), "webhook_count", matchingWebhooks.size()));
                } else if (matchingWebhooks.isEmpty()) {
                    unmatchedInternalCount++;
                    insertResult(runId, att.id(), null,
                            paymentId, att.providerOrderId(), "UNMATCHED_INTERNAL",
                            "Internal payment attempt has no verified provider capture webhook event.",
                            Map.of("attempt_state", att.state(), "amount_minor", att.amountMinor(), "currency", att.currencyCode()));
                } else {
                    WebhookCaptureRecord wh = matchingWebhooks.getFirst();
                    matchedWebhookIds.add(wh.id());
                    Long whAmount = wh.amountMinor();
                    if (whAmount == null && wh.safePayloadJson() != null) {
                        whAmount = extractAmountFromJson(wh.safePayloadJson());
                    }
                    String whCurrency = wh.currencyCode();
                    if (whCurrency == null && wh.safePayloadJson() != null) {
                        whCurrency = extractCurrencyFromJson(wh.safePayloadJson());
                    }

                    boolean amountMatches = whAmount != null && whAmount == att.amountMinor();
                    boolean currencyMatches = whCurrency != null && whCurrency.equalsIgnoreCase(att.currencyCode());
                    boolean stateCaptured = "CAPTURED_VERIFIED".equals(att.state());

                    if (!amountMatches || !currencyMatches) {
                        conflictingCount++;
                        insertResult(runId, att.id(), wh.id(),
                                paymentId, att.providerOrderId(), "CONFLICTING",
                                "Financial mismatch: amount or currency differs between attempt and provider evidence.",
                                Map.of("internal_amount", att.amountMinor(), "provider_amount", whAmount == null ? -1 : whAmount,
                                       "internal_currency", att.currencyCode(), "provider_currency", whCurrency == null ? "" : whCurrency));
                    } else if (!stateCaptured) {
                        conflictingCount++;
                        insertResult(runId, att.id(), wh.id(),
                                paymentId, att.providerOrderId(), "CONFLICTING",
                                "Lifecycle mismatch: internal state is " + att.state() + " but provider confirms capture.",
                                Map.of("internal_state", att.state(), "provider_status", "captured"));
                    } else {
                        matchedCount++;
                        insertResult(runId, att.id(), wh.id(),
                                paymentId, att.providerOrderId(), "MATCHED",
                                "Internal attempt matches verified provider webhook capture evidence.",
                                Map.of("amount_minor", att.amountMinor(), "currency", att.currencyCode(), "state", att.state()));
                    }
                }
            }

            // 2. Reconcile unmatched provider webhooks
            for (WebhookCaptureRecord wh : webhooks) {
                if (!matchedWebhookIds.contains(wh.id())) {
                    unmatchedProviderCount++;
                    insertResult(runId, null, wh.id(),
                            wh.providerPaymentId(), wh.providerOrderId(), "UNMATCHED_PROVIDER",
                            "Provider capture webhook has no corresponding internal payment attempt.",
                            Map.of("provider_payment_id", wh.providerPaymentId() == null ? "" : wh.providerPaymentId(),
                                   "provider_order_id", wh.providerOrderId() == null ? "" : wh.providerOrderId()));
                }
            }

            // Complete the run
            Instant completedAt = Instant.now();
            jdbc.update("""
                    UPDATE public.provider_payment_reconciliation_runs
                    SET status = 'COMPLETED',
                        total_internal_records = ?,
                        total_provider_records = ?,
                        matched_count = ?,
                        unmatched_internal_count = ?,
                        unmatched_provider_count = ?,
                        duplicate_count = ?,
                        conflicting_count = ?,
                        completed_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, attempts.size(), webhooks.size(), matchedCount,
                    unmatchedInternalCount, unmatchedProviderCount,
                    duplicateCount, conflictingCount, runId);

            return new ProviderPaymentReconciliationModels.RunSummary(
                    runId, runNumber, RULE_VERSION, "COMPLETED",
                    attempts.size(), webhooks.size(), matchedCount,
                    unmatchedInternalCount, unmatchedProviderCount,
                    duplicateCount, conflictingCount, startedAt, completedAt);
        });
    }

    public ApiPage<ProviderPaymentReconciliationModels.RunSummary> listRuns(int page, int size) {
        ReadPageRequest req = ReadPageRequest.validate(page, size);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM public.provider_payment_reconciliation_runs", Long.class);
        List<ProviderPaymentReconciliationModels.RunSummary> items = jdbc.query("""
                SELECT id, run_number, rule_version, status, total_internal_records,
                       total_provider_records, matched_count, unmatched_internal_count,
                       unmatched_provider_count, duplicate_count, conflicting_count,
                       started_at, completed_at
                FROM public.provider_payment_reconciliation_runs
                ORDER BY started_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, (rs, rowNum) -> mapRunSummary(rs), req.size(), req.offset());
        return ApiPage.of(items, page, size, total == null ? 0 : total);
    }

    public ProviderPaymentReconciliationModels.RunSummary getRunSummary(UUID runId) {
        List<ProviderPaymentReconciliationModels.RunSummary> list = jdbc.query("""
                SELECT id, run_number, rule_version, status, total_internal_records,
                       total_provider_records, matched_count, unmatched_internal_count,
                       unmatched_provider_count, duplicate_count, conflicting_count,
                       started_at, completed_at
                FROM public.provider_payment_reconciliation_runs
                WHERE id = ?
                """, (rs, rowNum) -> mapRunSummary(rs), runId);
        if (list.isEmpty()) {
            throw new ReadApiException(HttpStatus.NOT_FOUND, "RECONCILIATION_RUN_NOT_FOUND",
                    "The provider payment reconciliation run was not found.");
        }
        return list.getFirst();
    }

    public ApiPage<ProviderPaymentReconciliationModels.ResultItem> listResults(
            UUID runId, String outcomeFilter, int page, int size) {
        getRunSummary(runId); // verify run exists
        ReadPageRequest req = ReadPageRequest.validate(page, size);

        boolean hasFilter = outcomeFilter != null && !outcomeFilter.isBlank();
        String countSql = "SELECT count(*) FROM public.provider_payment_reconciliation_results WHERE run_id = ?"
                + (hasFilter ? " AND outcome = ?" : "");
        Long total = hasFilter
                ? jdbc.queryForObject(countSql, Long.class, runId, outcomeFilter.trim().toUpperCase())
                : jdbc.queryForObject(countSql, Long.class, runId);

        String querySql = """
                SELECT id, run_id, attempt_id, webhook_event_id, provider_payment_id, provider_order_id,
                       outcome, discrepancy_reason, details, created_at
                FROM public.provider_payment_reconciliation_results
                WHERE run_id = ?
                """ + (hasFilter ? " AND outcome = ? " : "") + """
                ORDER BY created_at ASC, id ASC
                LIMIT ? OFFSET ?
                """;

        List<ProviderPaymentReconciliationModels.ResultItem> items = hasFilter
                ? jdbc.query(querySql, (rs, rowNum) -> mapResultItem(rs), runId, outcomeFilter.trim().toUpperCase(), req.size(), req.offset())
                : jdbc.query(querySql, (rs, rowNum) -> mapResultItem(rs), runId, req.size(), req.offset());

        return ApiPage.of(items, page, size, total == null ? 0 : total);
    }

    private void insertResult(UUID runId, UUID attemptId, UUID webhookEventId,
                              String paymentId, String orderId, String outcome,
                              String reason, Map<String, Object> details) {
        String detailsJson;
        try {
            detailsJson = MAPPER.writeValueAsString(details);
        } catch (Exception e) {
            detailsJson = "{}";
        }
        jdbc.update("""
                INSERT INTO public.provider_payment_reconciliation_results
                    (id, run_id, attempt_id, webhook_event_id, provider_payment_id,
                     provider_order_id, outcome, discrepancy_reason, details)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                """, runId, attemptId, webhookEventId, paymentId, orderId, outcome, reason, detailsJson);
    }

    private static Long extractAmountFromJson(String json) {
        try {
            var node = MAPPER.readTree(json);
            if (node.has("amount_minor")) return node.path("amount_minor").asLong();
            if (node.has("amount")) return node.path("amount").asLong();
        } catch (Exception ignored) { }
        return null;
    }

    private static String extractCurrencyFromJson(String json) {
        try {
            var node = MAPPER.readTree(json);
            if (node.has("currency_code")) return node.path("currency_code").asText();
            if (node.has("currency")) return node.path("currency").asText();
        } catch (Exception ignored) { }
        return null;
    }

    private static ProviderPaymentReconciliationModels.RunSummary mapRunSummary(ResultSet rs) throws SQLException {
        Timestamp startedAt = rs.getTimestamp("started_at");
        Timestamp completedAt = rs.getTimestamp("completed_at");
        return new ProviderPaymentReconciliationModels.RunSummary(
                rs.getObject("id", UUID.class),
                rs.getInt("run_number"),
                rs.getString("rule_version"),
                rs.getString("status"),
                rs.getInt("total_internal_records"),
                rs.getInt("total_provider_records"),
                rs.getInt("matched_count"),
                rs.getInt("unmatched_internal_count"),
                rs.getInt("unmatched_provider_count"),
                rs.getInt("duplicate_count"),
                rs.getInt("conflicting_count"),
                startedAt == null ? null : startedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant());
    }

    private static ProviderPaymentReconciliationModels.ResultItem mapResultItem(ResultSet rs) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new ProviderPaymentReconciliationModels.ResultItem(
                rs.getObject("id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("webhook_event_id", UUID.class),
                rs.getString("provider_payment_id"),
                rs.getString("provider_order_id"),
                rs.getString("outcome"),
                rs.getString("discrepancy_reason"),
                rs.getString("details"),
                createdAt == null ? null : createdAt.toInstant());
    }

    private record AttemptRecord(UUID id, String receipt, long amountMinor, String currencyCode,
                                 String state, String providerOrderId, String providerPaymentId) { }
    private record WebhookCaptureRecord(UUID id, String providerEventId, String providerOrderId,
                                        String providerPaymentId, Long amountMinor, String currencyCode,
                                        String processingState, String safePayloadJson) { }
}
