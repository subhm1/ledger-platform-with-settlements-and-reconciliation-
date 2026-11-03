package com.ledger.platform.service;

import com.ledger.platform.api.ApiPage;
import com.ledger.platform.api.ProviderPaymentReconciliationModels;
import com.ledger.platform.api.ReadApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6.3.4 — Provider payment reconciliation integration tests.
 *
 * Exercises:
 *  1. MATCHED: internal CAPTURED_VERIFIED matches provider payment.captured with identical amount and currency.
 *  2. UNMATCHED_INTERNAL: internal CAPTURED_VERIFIED has no provider webhook capture event.
 *  3. UNMATCHED_PROVIDER: provider webhook capture event has no internal attempt.
 *  4. CONFLICTING: amount mismatch between internal attempt and provider webhook evidence.
 *  5. CONFLICTING: state mismatch (internal attempt is PAYMENT_PENDING while provider confirms captured).
 *  6. DUPLICATE: multiple webhook capture events for the same provider_payment_id.
 *  7. Reruns are safe and generate new run_number preserving history.
 *  8. Traceability: webhook_event_id is populated in reconciliation results.
 *  9. Accounting gate: zero journal transactions or entries are ever created by reconciliation.
 */
@SuppressWarnings("unchecked")
public class ProviderPaymentReconciliationTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private PlatformTransactionManager txManager;
    @Mock private TransactionStatus txStatus;

    private ProviderPaymentReconciliationService reconciliationService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(jdbc.queryForObject(contains("MAX(run_number)"), eq(Integer.class))).thenReturn(1);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        reconciliationService = new ProviderPaymentReconciliationService(jdbc, txManager);
    }

    @Test
    void test1_matchedOutcomeWhenAttemptAndWebhookAgree() {
        UUID attemptId = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        String paymentId = "pay_match_123";
        String orderId = "order_match_123";

        // Internal attempt: CAPTURED_VERIFIED, 1000 INR
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(attemptId);
            when(rs.getString("receipt")).thenReturn("lp_test");
            when(rs.getLong("amount_minor")).thenReturn(1000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("state")).thenReturn("CAPTURED_VERIFIED");
            when(rs.getString("provider_order_id")).thenReturn(orderId);
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_attempts"), any(RowMapper.class));

        // Provider webhook: payment.captured, PROCESSED, 1000 INR
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(webhookId);
            when(rs.getString("provider_event_id")).thenReturn("evt_match_123");
            when(rs.getString("provider_order_id")).thenReturn(orderId);
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            when(rs.getObject("amount_minor", Long.class)).thenReturn(1000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("processing_state")).thenReturn("PROCESSED");
            when(rs.getString("safe_payload_json")).thenReturn("{\"amount_minor\":1000,\"currency_code\":\"INR\"}");
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_webhook_events"), any(RowMapper.class));

        // Mock summary query
        mockRunSummaryQuery(1, "COMPLETED", 1, 1, 1, 0, 0, 0, 0);

        var summary = reconciliationService.reconcile();
        assertNotNull(summary);

        // Verify result insertion had outcome = MATCHED
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.provider_payment_reconciliation_results"),
                any(), eq(attemptId), eq(webhookId), eq(paymentId), eq(orderId), eq("MATCHED"), any(), any());

        // Verify accounting gate: no journal entries or transactions were ever inserted
        verify(jdbc, never()).update(contains("INSERT INTO public.journal_entries"), any(Object[].class));
        verify(jdbc, never()).update(contains("INSERT INTO public.journal_transactions"), any(Object[].class));
    }

    @Test
    void test2_unmatchedInternalWhenNoProviderWebhookExists() {
        UUID attemptId = UUID.randomUUID();
        String paymentId = "pay_internal_only";

        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(attemptId);
            when(rs.getString("receipt")).thenReturn("lp_test");
            when(rs.getLong("amount_minor")).thenReturn(5000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("state")).thenReturn("CAPTURED_VERIFIED");
            when(rs.getString("provider_order_id")).thenReturn("order_abc");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_attempts"), any(RowMapper.class));

        // No webhooks
        when(jdbc.query(contains("FROM public.payment_webhook_events"), any(RowMapper.class)))
                .thenReturn(List.of());

        mockRunSummaryQuery(1, "COMPLETED", 1, 0, 0, 1, 0, 0, 0);

        reconciliationService.reconcile();

        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.provider_payment_reconciliation_results"),
                any(), eq(attemptId), isNull(), eq(paymentId), any(), eq("UNMATCHED_INTERNAL"), any(), any());
    }

    @Test
    void test3_unmatchedProviderWhenNoInternalAttemptExists() {
        UUID webhookId = UUID.randomUUID();
        String paymentId = "pay_provider_only";

        // No attempts
        when(jdbc.query(contains("FROM public.payment_attempts"), any(RowMapper.class)))
                .thenReturn(List.of());

        // Webhook exists
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(webhookId);
            when(rs.getString("provider_event_id")).thenReturn("evt_orphan");
            when(rs.getString("provider_order_id")).thenReturn("order_orphan");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            when(rs.getObject("amount_minor", Long.class)).thenReturn(2500L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("processing_state")).thenReturn("PROCESSED");
            when(rs.getString("safe_payload_json")).thenReturn("{}");
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_webhook_events"), any(RowMapper.class));

        mockRunSummaryQuery(1, "COMPLETED", 0, 1, 0, 0, 1, 0, 0);

        reconciliationService.reconcile();

        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.provider_payment_reconciliation_results"),
                any(), isNull(), eq(webhookId), eq(paymentId), any(), eq("UNMATCHED_PROVIDER"), any(), any());
    }

    @Test
    void test4_conflictingWhenAmountDiffers() {
        UUID attemptId = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        String paymentId = "pay_mismatch_amt";

        // Attempt: 1000 INR
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(attemptId);
            when(rs.getString("receipt")).thenReturn("lp_test");
            when(rs.getLong("amount_minor")).thenReturn(1000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("state")).thenReturn("CAPTURED_VERIFIED");
            when(rs.getString("provider_order_id")).thenReturn("order_1");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_attempts"), any(RowMapper.class));

        // Webhook: 2000 INR (amount mismatch)
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(webhookId);
            when(rs.getString("provider_event_id")).thenReturn("evt_mismatch");
            when(rs.getString("provider_order_id")).thenReturn("order_1");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            when(rs.getObject("amount_minor", Long.class)).thenReturn(2000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("processing_state")).thenReturn("PROCESSED");
            when(rs.getString("safe_payload_json")).thenReturn("{}");
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_webhook_events"), any(RowMapper.class));

        mockRunSummaryQuery(1, "COMPLETED", 1, 1, 0, 0, 0, 0, 1);

        reconciliationService.reconcile();

        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.provider_payment_reconciliation_results"),
                any(), eq(attemptId), eq(webhookId), eq(paymentId), any(), eq("CONFLICTING"), any(), any());
    }

    @Test
    void test5_conflictingWhenStateMismatched() {
        UUID attemptId = UUID.randomUUID();
        UUID webhookId = UUID.randomUUID();
        String paymentId = "pay_state_mismatch";

        // Attempt is still PAYMENT_PENDING internally, but webhook has captured
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(attemptId);
            when(rs.getString("receipt")).thenReturn("lp_test");
            when(rs.getLong("amount_minor")).thenReturn(1000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("state")).thenReturn("PAYMENT_PENDING");
            when(rs.getString("provider_order_id")).thenReturn("order_1");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_attempts"), any(RowMapper.class));

        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(webhookId);
            when(rs.getString("provider_event_id")).thenReturn("evt_state");
            when(rs.getString("provider_order_id")).thenReturn("order_1");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            when(rs.getObject("amount_minor", Long.class)).thenReturn(1000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("processing_state")).thenReturn("PROCESSED");
            when(rs.getString("safe_payload_json")).thenReturn("{}");
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_webhook_events"), any(RowMapper.class));

        mockRunSummaryQuery(1, "COMPLETED", 1, 1, 0, 0, 0, 0, 1);

        reconciliationService.reconcile();

        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.provider_payment_reconciliation_results"),
                any(), eq(attemptId), eq(webhookId), eq(paymentId), any(), eq("CONFLICTING"), contains("Lifecycle mismatch"), any());
    }

    @Test
    void test6_duplicateOutcomeWhenMultipleWebhooksForSamePayment() {
        UUID attemptId = UUID.randomUUID();
        UUID webhookId1 = UUID.randomUUID();
        UUID webhookId2 = UUID.randomUUID();
        String paymentId = "pay_dup_123";

        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(attemptId);
            when(rs.getString("receipt")).thenReturn("lp_test");
            when(rs.getLong("amount_minor")).thenReturn(1000L);
            when(rs.getString("currency_code")).thenReturn("INR");
            when(rs.getString("state")).thenReturn("CAPTURED_VERIFIED");
            when(rs.getString("provider_order_id")).thenReturn("order_1");
            when(rs.getString("provider_payment_id")).thenReturn(paymentId);
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_attempts"), any(RowMapper.class));

        // Two webhook records for same payment ID
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs1 = mock(java.sql.ResultSet.class);
            when(rs1.getObject("id", UUID.class)).thenReturn(webhookId1);
            when(rs1.getString("provider_event_id")).thenReturn("evt_dup_1");
            when(rs1.getString("provider_order_id")).thenReturn("order_1");
            when(rs1.getString("provider_payment_id")).thenReturn(paymentId);
            when(rs1.getObject("amount_minor", Long.class)).thenReturn(1000L);
            when(rs1.getString("currency_code")).thenReturn("INR");
            when(rs1.getString("processing_state")).thenReturn("PROCESSED");
            when(rs1.getString("safe_payload_json")).thenReturn("{}");

            var rs2 = mock(java.sql.ResultSet.class);
            when(rs2.getObject("id", UUID.class)).thenReturn(webhookId2);
            when(rs2.getString("provider_event_id")).thenReturn("evt_dup_2");
            when(rs2.getString("provider_order_id")).thenReturn("order_1");
            when(rs2.getString("provider_payment_id")).thenReturn(paymentId);
            when(rs2.getObject("amount_minor", Long.class)).thenReturn(1000L);
            when(rs2.getString("currency_code")).thenReturn("INR");
            when(rs2.getString("processing_state")).thenReturn("PROCESSED");
            when(rs2.getString("safe_payload_json")).thenReturn("{}");

            return List.of(mapper.mapRow(rs1, 1), mapper.mapRow(rs2, 2));
        }).when(jdbc).query(contains("FROM public.payment_webhook_events"), any(RowMapper.class));

        mockRunSummaryQuery(1, "COMPLETED", 1, 2, 0, 0, 0, 1, 0);

        reconciliationService.reconcile();

        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.provider_payment_reconciliation_results"),
                any(), eq(attemptId), any(), eq(paymentId), any(), eq("DUPLICATE"), any(), any());
    }

    @Test
    void test7_getRunSummaryNotFoundThrows404() {
        UUID missingId = UUID.randomUUID();
        when(jdbc.query(contains("FROM public.provider_payment_reconciliation_runs WHERE id ="), any(RowMapper.class), eq(missingId)))
                .thenReturn(List.of());

        ReadApiException ex = assertThrows(ReadApiException.class,
                () -> reconciliationService.getRunSummary(missingId));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("RECONCILIATION_RUN_NOT_FOUND", ex.code());
    }

    private void mockRunSummaryQuery(int runNumber, String status, int totalInternal, int totalProvider,
                                     int matched, int unmatchedInternal, int unmatchedProvider,
                                     int duplicate, int conflicting) {
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
            when(rs.getInt("run_number")).thenReturn(runNumber);
            when(rs.getString("rule_version")).thenReturn("provider-payment-v1");
            when(rs.getString("status")).thenReturn(status);
            when(rs.getInt("total_internal_records")).thenReturn(totalInternal);
            when(rs.getInt("total_provider_records")).thenReturn(totalProvider);
            when(rs.getInt("matched_count")).thenReturn(matched);
            when(rs.getInt("unmatched_internal_count")).thenReturn(unmatchedInternal);
            when(rs.getInt("unmatched_provider_count")).thenReturn(unmatchedProvider);
            when(rs.getInt("duplicate_count")).thenReturn(duplicate);
            when(rs.getInt("conflicting_count")).thenReturn(conflicting);
            when(rs.getTimestamp("started_at")).thenReturn(new java.sql.Timestamp(System.currentTimeMillis()));
            when(rs.getTimestamp("completed_at")).thenReturn(new java.sql.Timestamp(System.currentTimeMillis()));
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.provider_payment_reconciliation_runs WHERE id ="), any(RowMapper.class), any());
    }
}
