package com.ledger.platform.service;

import com.ledger.platform.api.ProviderPaymentModels;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 6.3.2 — Webhook intake and verification unit tests.
 *
 * Tests:
 *  1.  Missing body rejected with 400; no DB side effects.
 *  2.  Missing signature rejected with 400; no DB side effects.
 *  3.  Invalid signature rejected with 400; no DB side effects.
 *  4.  Valid signature with supported event persists row and returns OK.
 *  5.  Duplicate event (DB conflict detected) returns OK without re-processing.
 *  6.  Unsupported event type is persisted and marked REVIEW_REQUIRED, returns OK.
 *  7.  Malformed JSON body is persisted with empty event type, returns OK.
 *  8.  payment.captured transitions attempt to CAPTURED_VERIFIED.
 *  9.  payment.authorized transitions attempt to AUTHORIZED.
 * 10.  payment.failed does NOT mark attempt FAILED; records PAYMENT_PENDING.
 * 11.  payment.captured on already-CAPTURED_VERIFIED attempt is idempotent.
 * 12.  Webhook not configured returns 503.
 */
@SuppressWarnings("unchecked")
public class WebhookIntakeVerificationTest {

    private static final String WEBHOOK_SECRET = "test_webhook_secret";
    private static final UUID ATTEMPT_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final String RECEIPT = "lp_aaaaaaaa00004000800000000000001";
    private static final String ORDER_ID = "order_TEST123";
    private static final String PAYMENT_ID = "pay_TEST456";

    @Mock private JdbcTemplate jdbc;
    @Mock private PlatformTransactionManager txMgr;
    @Mock private RazorpayConfiguration cfg;
    @Mock private RazorpayGateway gateway;
    @Mock private TransactionStatus txStatus;

    private ProviderPaymentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(cfg.webhookConfigured()).thenReturn(true);
        when(cfg.webhookSecret()).thenReturn(WEBHOOK_SECRET);
        when(cfg.configured()).thenReturn(true);
        when(cfg.keyId()).thenReturn("rzp_test_key");
        // Allow TransactionTemplate to run callbacks synchronously.
        when(txMgr.getTransaction(any())).thenReturn(txStatus);
        // Default: no existing webhook row (non-duplicate).
        when(jdbc.query(contains("WHERE provider = 'RAZORPAY'"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of());
        // Default: all updates succeed.
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        service = new ProviderPaymentService(jdbc, txMgr, cfg, gateway);
    }

    // ---- Helpers ----

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    private byte[] capturedPayload(String eventType) {
        return ("{\"event\":\"" + eventType + "\","
                + "\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\"" + PAYMENT_ID + "\","
                + "\"order_id\":\"" + ORDER_ID + "\","
                + "\"amount\":10000,\"currency\":\"INR\",\"status\":\"captured\"}},"
                + "\"order\":{\"entity\":{\"id\":\"" + ORDER_ID + "\","
                + "\"receipt\":\"" + RECEIPT + "\","
                + "\"amount\":10000,\"currency\":\"INR\",\"status\":\"paid\"}}}}").getBytes(StandardCharsets.UTF_8);
    }

    private ProviderPaymentService.AttemptRow attemptRow(String state) {
        return new ProviderPaymentService.AttemptRow(
                ATTEMPT_ID, RECEIPT, 10000L, "INR", "purpose", "ref",
                state, ORDER_ID, PAYMENT_ID, Instant.now(), Instant.now(), "fp");
    }

    // ---- Tests ----

    // 1. Missing body → 400; no DB writes
    @Test
    void test1_missingBodyRejected() {
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class,
                () -> service.ingest(new byte[0], "someSig", "evt_1"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.code());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // 2. Missing signature → 400; no DB writes
    @Test
    void test2_missingSignatureRejected() {
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class,
                () -> service.ingest("{\"event\":\"payment.captured\"}".getBytes(StandardCharsets.UTF_8), null, "evt_2"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_WEBHOOK_PAYLOAD", ex.code());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    // 3. Invalid signature → 400; no DB writes at all
    @Test
    void test3_invalidSignatureRejectedNoDbWrites() {
        byte[] body = capturedPayload("payment.captured");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class,
                () -> service.ingest(body, "aW52YWxpZA==", "evt_3"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.code());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    // 4. Valid signature + supported event → persisted row + returns OK
    @Test
    void test4_validSignatureSupportedEventPersistedAndOk() throws Exception {
        byte[] body = capturedPayload("payment.captured");
        String sig = sign(body);
        // Attempt lookup by receipt.
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));
        // Attempt locked for transition.
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));

        ProviderPaymentModels.WebhookAck ack = service.ingest(body, sig, "evt_4");

        assertEquals("OK", ack.status());
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.payment_webhook_events"), any(Object[].class));
    }

    // 5. Duplicate event (DB conflict) → returns OK without re-processing
    @Test
    void test5_duplicateEventReturnsOkWithoutReprocessing() throws Exception {
        byte[] body = capturedPayload("payment.captured");
        String sig = sign(body);
        // Simulate existing row found in DB.
        when(jdbc.query(contains("WHERE provider = 'RAZORPAY'"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(UUID.randomUUID()));

        ProviderPaymentModels.WebhookAck ack = service.ingest(body, sig, "evt_dup_5");

        assertEquals("OK", ack.status());
        verify(jdbc, never()).update(contains("INSERT INTO public.payment_webhook_events"), any(Object[].class));
        verify(jdbc, never()).update(contains("UPDATE public.payment_attempts"), any(Object[].class));
    }

    // 6. Unsupported event type → persisted, marked REVIEW_REQUIRED, returns OK
    @Test
    void test6_unsupportedEventTypePersistedAsReviewRequired() throws Exception {
        byte[] body = "{\"event\":\"refund.created\",\"payload\":{}}".getBytes(StandardCharsets.UTF_8);
        String sig = sign(body);

        ProviderPaymentModels.WebhookAck ack = service.ingest(body, sig, "evt_6");

        assertEquals("OK", ack.status());
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.payment_webhook_events"), any(Object[].class));
        verify(jdbc, atLeastOnce()).update(contains("REVIEW_REQUIRED"), any(Object[].class));
        verify(jdbc, never()).update(contains("UPDATE public.payment_attempts"), any(Object[].class));
    }

    // 7. Malformed JSON body → persisted with UNKNOWN event type, returns OK
    @Test
    void test7_malformedJsonPersistedAndOk() throws Exception {
        byte[] body = "not-valid-json".getBytes(StandardCharsets.UTF_8);
        String sig = sign(body);

        ProviderPaymentModels.WebhookAck ack = service.ingest(body, sig, "evt_7");

        assertEquals("OK", ack.status());
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.payment_webhook_events"), any(Object[].class));
    }

    // 8. payment.captured → attempt transitions to CAPTURED_VERIFIED
    @Test
    void test8_paymentCapturedTransitionsToCapturedVerified() throws Exception {
        byte[] body = capturedPayload("payment.captured");
        String sig = sign(body);
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));

        service.ingest(body, sig, "evt_8");

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains("UPDATE public.payment_attempts"), captor.capture());
        boolean capturedVerifiedFound = captor.getAllValues().stream()
                .anyMatch(args -> args.length > 0 && "CAPTURED_VERIFIED".equals(args[0]));
        assertTrue(capturedVerifiedFound, "Expected UPDATE to CAPTURED_VERIFIED");
    }

    // 9. payment.authorized → attempt transitions to AUTHORIZED
    @Test
    void test9_paymentAuthorizedTransitionsToAuthorized() throws Exception {
        byte[] body = capturedPayload("payment.authorized");
        String sig = sign(body);
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));

        service.ingest(body, sig, "evt_9");

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeastOnce()).update(contains("UPDATE public.payment_attempts"), captor.capture());
        boolean authorizedFound = captor.getAllValues().stream()
                .anyMatch(args -> args.length > 0 && "AUTHORIZED".equals(args[0]));
        assertTrue(authorizedFound, "Expected UPDATE to AUTHORIZED");
    }

    // 10. payment.failed → must NOT mark attempt FAILED; records PAYMENT_PENDING
    @Test
    void test10_paymentFailedDoesNotMarkFailed() throws Exception {
        byte[] body = ("{\"event\":\"payment.failed\","
                + "\"payload\":{"
                + "\"payment\":{\"entity\":{\"id\":\"" + PAYMENT_ID + "\","
                + "\"order_id\":\"" + ORDER_ID + "\","
                + "\"amount\":10000,\"currency\":\"INR\",\"status\":\"failed\"}},"
                + "\"order\":{\"entity\":{\"id\":\"" + ORDER_ID + "\","
                + "\"receipt\":\"" + RECEIPT + "\","
                + "\"amount\":10000,\"currency\":\"INR\"}}}}").getBytes(StandardCharsets.UTF_8);
        String sig = sign(body);
        // Attempt already in PAYMENT_PENDING — transition is an idempotent no-op (same state).
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));

        ProviderPaymentModels.WebhookAck ack = service.ingest(body, sig, "evt_10");
        assertEquals("OK", ack.status());

        // Capture all UPDATE payment_attempts calls (if any) and verify none set state to FAILED.
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(0)).update(contains("UPDATE public.payment_attempts"), captor.capture());
        boolean failedStateFound = captor.getAllValues().stream()
                .anyMatch(args -> args.length > 0 && "FAILED".equals(args[0]));
        assertFalse(failedStateFound, "payment.failed webhook must NOT set state to FAILED");

        // Must be marked PROCESSED (not REVIEW_REQUIRED).
        verify(jdbc, atLeastOnce()).update(contains("PROCESSED"), any(Object[].class));
    }

    // 11. payment.captured on already CAPTURED_VERIFIED → idempotent: no UPDATE on payment_attempts
    @Test
    void test11_captureWebhookOnAlreadyCapturedIsIdempotent() throws Exception {
        byte[] body = capturedPayload("payment.captured");
        String sig = sign(body);
        // Attempt is already CAPTURED_VERIFIED.
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("CAPTURED_VERIFIED")));

        service.ingest(body, sig, "evt_11");

        // Must NOT update the attempt to a new state.
        verify(jdbc, never()).update(contains("UPDATE public.payment_attempts"), any(Object[].class));
        // Must insert an observation event.
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.payment_attempt_events"), any(Object[].class));
    }

    // 12. Webhook not configured → 503
    @Test
    void test12_webhookNotConfiguredReturns503() {
        when(cfg.webhookConfigured()).thenReturn(false);
        byte[] body = capturedPayload("payment.captured");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class,
                () -> service.ingest(body, "anySig", "evt_12"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.status());
        assertEquals("WEBHOOK_NOT_CONFIGURED", ex.code());
    }

    // 13. Replay not found → 404
    @Test
    void test13_replayNotFoundThrows404() {
        UUID missingId = UUID.randomUUID();
        when(jdbc.query(contains("FROM public.payment_webhook_events WHERE id ="), any(RowMapper.class), eq(missingId)))
                .thenReturn(List.of());

        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class,
                () -> service.replayWebhookEvent(missingId));
        assertEquals(HttpStatus.NOT_FOUND, ex.status());
        assertEquals("WEBHOOK_EVENT_NOT_FOUND", ex.code());
    }

    // 14. Replay valid event → transitions through PROCESSING to PROCESSED
    @Test
    void test14_replayValidEventSucceeds() {
        UUID eventId = UUID.randomUUID();
        doAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            var rs = mock(java.sql.ResultSet.class);
            when(rs.getObject("id", UUID.class)).thenReturn(eventId);
            when(rs.getString("event_type")).thenReturn("payment.captured");
            when(rs.getString("provider_order_id")).thenReturn(ORDER_ID);
            when(rs.getString("provider_payment_id")).thenReturn(PAYMENT_ID);
            when(rs.getString("processing_state")).thenReturn("REVIEW_REQUIRED");
            when(rs.getString("safe_payload_json")).thenReturn("{\"receipt\":\"" + RECEIPT + "\"}");
            return List.of(mapper.mapRow(rs, 1));
        }).when(jdbc).query(contains("FROM public.payment_webhook_events WHERE id ="), any(RowMapper.class), eq(eventId));

        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));
        when(jdbc.query(contains("FOR UPDATE"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("PAYMENT_PENDING")));

        ProviderPaymentModels.WebhookAck ack = service.replayWebhookEvent(eventId);
        assertEquals("REPLAYED", ack.status());

        verify(jdbc, atLeastOnce()).update(contains("UPDATE public.payment_webhook_events"), any(Object[].class));
    }

    // 15. Out-of-order: payment.authorized arriving after payment.captured does not regress CAPTURED_VERIFIED
    @Test
    void test15_authorizedAfterCapturedDoesNotRegressState() throws Exception {
        byte[] body = capturedPayload("payment.authorized");
        String sig = sign(body);
        // Attempt is already CAPTURED_VERIFIED
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("CAPTURED_VERIFIED")));

        service.ingest(body, sig, "evt_ooo_15");

        // Must NOT regress state to AUTHORIZED
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(0)).update(contains("UPDATE public.payment_attempts"), captor.capture());
        boolean authorizedUpdate = captor.getAllValues().stream()
                .anyMatch(args -> args.length > 0 && "AUTHORIZED".equals(args[0]));
        assertFalse(authorizedUpdate, "payment.authorized arriving late must NOT regress CAPTURED_VERIFIED state");

        // Evidence is preserved via payment_attempt_events
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.payment_attempt_events"), any(Object[].class));
    }

    // 16. Out-of-order: payment.failed arriving after payment.authorized does not regress AUTHORIZED
    @Test
    void test16_failedAfterAuthorizedDoesNotRegressState() throws Exception {
        byte[] body = capturedPayload("payment.failed");
        String sig = sign(body);
        // Attempt is already AUTHORIZED
        when(jdbc.query(contains("FROM public.payment_attempts WHERE receipt"), any(RowMapper.class), any(Object.class)))
                .thenReturn(List.of(attemptRow("AUTHORIZED")));

        service.ingest(body, sig, "evt_ooo_16");

        // Must NOT regress state to PAYMENT_PENDING
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, atLeast(0)).update(contains("UPDATE public.payment_attempts"), captor.capture());
        boolean pendingUpdate = captor.getAllValues().stream()
                .anyMatch(args -> args.length > 0 && "PAYMENT_PENDING".equals(args[0]));
        assertFalse(pendingUpdate, "payment.failed arriving late must NOT regress AUTHORIZED state");

        // Evidence is preserved via payment_attempt_events
        verify(jdbc, atLeastOnce()).update(contains("INSERT INTO public.payment_attempt_events"), any(Object[].class));
    }
}
