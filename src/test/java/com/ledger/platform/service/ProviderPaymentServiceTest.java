package com.ledger.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ledger.platform.api.ProviderPaymentModels;
import com.ledger.platform.service.ProviderPaymentService.Created;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Simple unit tests for {@link ProviderPaymentService},
 * focusing on request validation and idempotency handling.
 * The tests use Mockito to stub out database and Razorpay interactions, exercising only the service logic.
 */
public class ProviderPaymentServiceTest {

    @Mock
    private JdbcTemplate jdbc;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private RazorpayConfiguration configuration;
    @Mock
    private RazorpayGateway gateway;

    private ProviderPaymentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // Configuration is considered "configured" for all tests.
        when(configuration.configured()).thenReturn(true);
        when(configuration.keyId()).thenReturn("rzp_test_12345");
        // Gateway never actually called in the validation test; safe stub.
        when(gateway.createOrder(anyString(), anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("Gateway should not be invoked in this test"));
        service = new ProviderPaymentService(jdbc, transactionManager, configuration, gateway);
    }

    @Test
    void createRejectsNegativeAmount() {
        ProviderPaymentModels.CreateRequest request = new ProviderPaymentModels.CreateRequest(
                -100L, "INR", "Test purpose", "ref-001");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> {
            service.create(request, "key-123", "operator@example.com");
        });
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_AMOUNT", ex.code());
    }

    @Test
    void createRejectsBlankPurpose() {
        ProviderPaymentModels.CreateRequest request = new ProviderPaymentModels.CreateRequest(
                5000L, "INR", "  ", "ref-001");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> {
            service.create(request, "key-123", "operator@example.com");
        });
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_PURPOSE", ex.code());
    }

    @Test
    void createRejectsUnsupportedCurrency() {
        ProviderPaymentModels.CreateRequest request = new ProviderPaymentModels.CreateRequest(
                5000L, "USD", "Test purpose", "ref-001");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> {
            service.create(request, "key-123", "operator@example.com");
        });
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_CURRENCY", ex.code());
    }

    @Test
    void createRejectsBlankIdempotencyKey() {
        ProviderPaymentModels.CreateRequest request = new ProviderPaymentModels.CreateRequest(
                5000L, "INR", "Test purpose", "ref-001");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> {
            service.create(request, "  ", "operator@example.com");
        });
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_IDEMPOTENCY_KEY", ex.code());
    }

    @Test
    void createRejectsUnauthenticatedOperator() {
        ProviderPaymentModels.CreateRequest request = new ProviderPaymentModels.CreateRequest(
                5000L, "INR", "Test purpose", "ref-001");
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> {
            service.create(request, "key-123", "");
        });
        assertEquals(HttpStatus.UNAUTHORIZED, ex.status());
        assertEquals("UNAUTHENTICATED", ex.code());
    }
}
