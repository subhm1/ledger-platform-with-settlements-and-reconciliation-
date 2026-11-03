package com.ledger.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ledger.platform.api.ProviderPaymentModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ProviderPaymentFailureHandlingTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private PlatformTransactionManager txManager;
    @Mock private RazorpayConfiguration configuration;
    @Mock private RazorpayGateway gateway;
    @Mock private TransactionStatus txStatus;

    private ProviderPaymentService service;
    private final UUID attemptId = UUID.randomUUID();
    private final String orderId = "order_test123456";
    private final String receipt = "lp_" + attemptId.toString().replace("-", "");
    private final String paymentId = "pay_test789012";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(configuration.configured()).thenReturn(true);
        when(configuration.keyId()).thenReturn("rzp_test_12345");
        when(configuration.apiSecret()).thenReturn("test_api_secret_67890");
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        service = new ProviderPaymentService(jdbc, txManager, configuration, gateway);
    }

    private void mockAttempt(String state, String providerPaymentId) {
        ProviderPaymentService.AttemptRow row = new ProviderPaymentService.AttemptRow(
                attemptId, receipt, 1000L, "INR", "Collection", "ref-001",
                state, orderId, providerPaymentId, Instant.now(), Instant.now(), "fingerprint");
        when(jdbc.query(contains("payment_attempts WHERE id ="), any(RowMapper.class), eq(attemptId)))
                .thenReturn(List.of(row));
        when(jdbc.query(contains("payment_attempt_events WHERE attempt_id ="), any(RowMapper.class), eq(attemptId)))
                .thenReturn(Collections.emptyList());
    }

    // 1. Client failure callback with provider status failed
    @Test
    void test1_clientFailureCallbackWithProviderStatusFailed() {
        mockAttempt("READY_FOR_CHECKOUT", null);
        when(gateway.fetchPayment(paymentId)).thenReturn(Map.of(
                "id", paymentId,
                "order_id", orderId,
                "amount", 1000L,
                "currency", "INR",
                "status", "failed"
        ));
        when(gateway.fetchOrder(orderId)).thenReturn(Map.of(
                "id", orderId,
                "receipt", receipt,
                "amount", 1000L,
                "currency", "INR",
                "status", "attempted",
                "amount_paid", 0L,
                "amount_due", 1000L,
                "attempts", 1L
        ));

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        verify(jdbc).update(contains("UPDATE public.payment_attempts SET state = ?"),
                eq("FAILED"), isNull(), eq(paymentId), eq("PROVIDER_PAYMENT_FAILED"), eq(attemptId));
        verify(jdbc).update(contains("INSERT INTO public.payment_attempt_events"),
                eq(attemptId), eq("PAYMENT_FAILED"), eq("READY_FOR_CHECKOUT"), eq("FAILED"), contains("failed payment"));
    }

    // 2. Client failure callback with provider status created (do not mark FAILED)
    @Test
    void test2_clientFailureCallbackWithProviderStatusCreated() {
        mockAttempt("READY_FOR_CHECKOUT", null);
        when(gateway.fetchPayment(paymentId)).thenReturn(Map.of(
                "id", paymentId,
                "order_id", orderId,
                "amount", 1000L,
                "currency", "INR",
                "status", "created"
        ));
        when(gateway.fetchOrder(orderId)).thenReturn(Map.of(
                "id", orderId,
                "receipt", receipt,
                "amount", 1000L,
                "currency", "INR",
                "status", "attempted",
                "amount_paid", 0L,
                "amount_due", 1000L,
                "attempts", 1L
        ));

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        // Must transition to PAYMENT_PENDING, NOT FAILED
        verify(jdbc).update(contains("UPDATE public.payment_attempts SET state = ?"),
                eq("PAYMENT_PENDING"), isNull(), eq(paymentId), eq("PROVIDER_PAYMENT_PENDING"), eq(attemptId));
        verify(jdbc).update(contains("INSERT INTO public.payment_attempt_events"),
                eq(attemptId), eq("PAYMENT_UNRESOLVED"), eq("READY_FOR_CHECKOUT"), eq("PAYMENT_PENDING"), contains("not yet confirmed"));
    }

    // 3. Client failure callback with provider status authorized (signature missing -> REVIEW_REQUIRED)
    @Test
    void test3_clientFailureCallbackWithProviderStatusAuthorized() {
        mockAttempt("READY_FOR_CHECKOUT", null);
        when(gateway.fetchPayment(paymentId)).thenReturn(Map.of(
                "id", paymentId,
                "order_id", orderId,
                "amount", 1000L,
                "currency", "INR",
                "status", "authorized",
                "captured", false
        ));
        when(gateway.fetchOrder(orderId)).thenReturn(Map.of(
                "id", orderId,
                "receipt", receipt,
                "amount", 1000L,
                "currency", "INR",
                "status", "attempted",
                "amount_paid", 0L,
                "amount_due", 1000L,
                "attempts", 1L
        ));

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        // Must transition to REVIEW_REQUIRED because provider reports authorized but client has no signature
        verify(jdbc).update(contains("UPDATE public.payment_attempts SET state = ?"),
                eq("REVIEW_REQUIRED"), isNull(), eq(paymentId), eq("SIGNATURE_MISSING_FOR_AUTHORIZED_PAYMENT"), eq(attemptId));
    }

    // 4. Client failure callback with provider status captured (signature missing -> REVIEW_REQUIRED)
    @Test
    void test4_clientFailureCallbackWithProviderStatusCaptured() {
        mockAttempt("READY_FOR_CHECKOUT", null);
        when(gateway.fetchPayment(paymentId)).thenReturn(Map.of(
                "id", paymentId,
                "order_id", orderId,
                "amount", 1000L,
                "currency", "INR",
                "status", "captured",
                "captured", true
        ));
        when(gateway.fetchOrder(orderId)).thenReturn(Map.of(
                "id", orderId,
                "receipt", receipt,
                "amount", 1000L,
                "currency", "INR",
                "status", "paid",
                "amount_paid", 1000L,
                "amount_due", 0L,
                "attempts", 1L
        ));

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        // Must transition to REVIEW_REQUIRED because signature cannot be bypassed for captured payment
        verify(jdbc).update(contains("UPDATE public.payment_attempts SET state = ?"),
                eq("REVIEW_REQUIRED"), isNull(), eq(paymentId), eq("SIGNATURE_MISSING_FOR_CAPTURED_PAYMENT"), eq(attemptId));
    }

    // 5. Checkout dismissal without a payment ID
    @Test
    void test5_checkoutDismissalWithoutPaymentId() {
        mockAttempt("READY_FOR_CHECKOUT", null);

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(null, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        // Does NOT update state on payment_attempts
        verify(jdbc, never()).update(startsWith("UPDATE public.payment_attempts"), any(Object[].class));
        // Records CHECKOUT_DISMISSED observation event
        verify(jdbc).update(contains("INSERT INTO public.payment_attempt_events"),
                eq(attemptId), eq("CHECKOUT_DISMISSED"), eq("READY_FOR_CHECKOUT"), eq("READY_FOR_CHECKOUT"), contains("modal dismissed"));
    }

    // 6. Invalid or mismatched order/payment IDs
    @Test
    void test6_invalidOrMismatchedOrderOrPaymentIds() {
        mockAttempt("READY_FOR_CHECKOUT", null);

        // Mismatched order ID
        ProviderPaymentModels.CheckoutVerification mismatchedOrder = new ProviderPaymentModels.CheckoutVerification(paymentId, "order_wrong", null);
        ProviderPaymentException ex1 = assertThrows(ProviderPaymentException.class, () -> service.verifyCheckout(attemptId, mismatchedOrder));
        assertEquals(HttpStatus.BAD_REQUEST, ex1.status());
        assertEquals("ORDER_ID_MISMATCH", ex1.code());

        // Incomplete request
        ProviderPaymentModels.CheckoutVerification emptyOrder = new ProviderPaymentModels.CheckoutVerification(paymentId, "", null);
        ProviderPaymentException ex2 = assertThrows(ProviderPaymentException.class, () -> service.verifyCheckout(attemptId, emptyOrder));
        assertEquals(HttpStatus.BAD_REQUEST, ex2.status());
        assertEquals("INVALID_CHECKOUT_RESPONSE", ex2.code());

        // Payment not found in provider
        when(gateway.fetchPayment(paymentId)).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        ProviderPaymentModels.CheckoutVerification notFoundReq = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentException ex3 = assertThrows(ProviderPaymentException.class, () -> service.verifyCheckout(attemptId, notFoundReq));
        assertEquals(HttpStatus.BAD_REQUEST, ex3.status());
        assertEquals("PROVIDER_PAYMENT_NOT_FOUND", ex3.code());
    }

    // 7. Duplicate failure callbacks
    @Test
    void test7_duplicateFailureCallbacksAreIdempotent() {
        // Attempt already in FAILED with paymentId
        mockAttempt("FAILED", paymentId);

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        assertEquals("FAILED", detail.attempt().state());
        // Does not call gateway or update database again
        verifyNoInteractions(gateway);
        verify(jdbc, never()).update(startsWith("UPDATE public.payment_attempts"), any(Object[].class));
    }

    // 8. Provider API timeout
    @Test
    void test8_providerApiTimeoutTransitionsToPaymentPending() {
        mockAttempt("READY_FOR_CHECKOUT", null);
        when(gateway.fetchPayment(paymentId)).thenThrow(new RestClientException("Connection timed out"));

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        verify(jdbc).update(contains("UPDATE public.payment_attempts SET state = ?"),
                eq("PAYMENT_PENDING"), isNull(), isNull(), eq("PROVIDER_VERIFICATION_PENDING"), eq(attemptId));
        verify(jdbc).update(contains("INSERT INTO public.payment_attempt_events"),
                eq(attemptId), eq("VERIFICATION_PENDING"), eq("READY_FOR_CHECKOUT"), eq("PAYMENT_PENDING"), contains("verification is pending"));
    }

    // 9. Repeated recovery requests
    @Test
    void test9_repeatedRecoveryRequests() {
        mockAttempt("ORDER_UNKNOWN", null);
        when(gateway.findOrdersByReceipt(receipt)).thenReturn(Collections.emptyList());

        // First recovery request
        ProviderPaymentModels.Detail detail1 = service.recoverOrder(attemptId);
        assertNotNull(detail1);

        // Second recovery request with still no matches
        ProviderPaymentModels.Detail detail2 = service.recoverOrder(attemptId);
        assertNotNull(detail2);

        // Once recovered to READY_FOR_CHECKOUT, calling recoverOrder again fails with ORDER_NOT_UNKNOWN
        mockAttempt("READY_FOR_CHECKOUT", null);
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> service.recoverOrder(attemptId));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("ORDER_NOT_UNKNOWN", ex.code());
    }

    // 10. No premature ledger posting
    @Test
    void test10_noPrematureLedgerPosting() {
        // Across all failure and pending states, accountingState must remain NOT_CAPTURED
        mockAttempt("FAILED", paymentId);
        ProviderPaymentModels.Detail failedDetail = service.detail(attemptId);
        assertEquals("NOT_CAPTURED", failedDetail.accountingState());

        mockAttempt("PAYMENT_PENDING", paymentId);
        ProviderPaymentModels.Detail pendingDetail = service.detail(attemptId);
        assertEquals("NOT_CAPTURED", pendingDetail.accountingState());

        mockAttempt("REVIEW_REQUIRED", paymentId);
        ProviderPaymentModels.Detail reviewDetail = service.detail(attemptId);
        assertEquals("NOT_CAPTURED", reviewDetail.accountingState());

        mockAttempt("READY_FOR_CHECKOUT", null);
        ProviderPaymentModels.Detail readyDetail = service.detail(attemptId);
        assertEquals("NOT_CAPTURED", readyDetail.accountingState());

        // Even for CAPTURED_VERIFIED, it is gate pending, and ledger tables are never queried/updated
        mockAttempt("CAPTURED_VERIFIED", paymentId);
        ProviderPaymentModels.Detail capturedDetail = service.detail(attemptId);
        assertEquals("ACCOUNTING_GATE_PENDING", capturedDetail.accountingState());

        verify(jdbc, never()).update(startsWith("INSERT INTO public.journal_entries"), any(Object[].class));
        verify(jdbc, never()).update(startsWith("INSERT INTO public.journal_entry_lines"), any(Object[].class));
    }

    // 11. Repeated verification in PAYMENT_PENDING does not insert duplicate events
    @Test
    void test11_duplicatePaymentPendingCallbacksDoNotInsertDuplicateEvents() {
        mockAttempt("PAYMENT_PENDING", paymentId);
        when(gateway.fetchPayment(paymentId)).thenReturn(Map.of(
                "id", paymentId,
                "order_id", orderId,
                "amount", 1000L,
                "currency", "INR",
                "status", "created",
                "captured", false
        ));
        when(gateway.fetchOrder(orderId)).thenReturn(Map.of(
                "id", orderId,
                "receipt", receipt,
                "amount", 1000L,
                "amount_paid", 0L,
                "amount_due", 1000L,
                "currency", "INR",
                "status", "attempted",
                "attempts", 1L
        ));

        ProviderPaymentModels.CheckoutVerification req = new ProviderPaymentModels.CheckoutVerification(paymentId, orderId, null);
        ProviderPaymentModels.Detail detail = service.verifyCheckout(attemptId, req);

        assertNotNull(detail);
        assertEquals("PAYMENT_PENDING", detail.attempt().state());
        // Verify no update was issued on payment_attempts
        verify(jdbc, never()).update(startsWith("UPDATE public.payment_attempts"), any(Object[].class));
        // Verify no duplicate event was inserted into payment_attempt_events
        verify(jdbc, never()).update(contains("INSERT INTO public.payment_attempt_events"), any(Object[].class));
    }
}
