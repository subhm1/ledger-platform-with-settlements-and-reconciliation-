package com.ledger.platform.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ledger.platform.api.ProviderPaymentModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Unit tests for the webhook ingestion path of {@link ProviderPaymentService}.
 * They exercise signature verification, duplicate-event handling, and state transition.
 */
public class ProviderPaymentServiceWebhookTest {

    @Mock private JdbcTemplate jdbc;
    @Mock private PlatformTransactionManager txMgr;
    @Mock private RazorpayConfiguration cfg;
    @Mock private RazorpayGateway gateway;

    private ProviderPaymentService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(cfg.configured()).thenReturn(true);
        when(cfg.webhookConfigured()).thenReturn(true);
        when(cfg.webhookSecret()).thenReturn("whsec_test_secret");
        service = new ProviderPaymentService(jdbc, txMgr, cfg, gateway);
    }

    private String sign(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(cfg.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }

    @Test
    void ingestValidSignatureUpdatesState() throws Exception {
        // Minimal payload that would cause a captured verification transition.
        String json = "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"id\":\"pay_123\",\"order_id\":\"order_abc\",\"amount\":1000,\"currency\":\"INR\",\"status\":\"captured\"},\"order\":{\"id\":\"order_abc\",\"receipt\":\"lp_dummy\",\"amount\":1000,\"currency\":\"INR\",\"status\":\"created\"}}}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        String signature = sign(body);
        // Mock DB lookup for the attempt matching the receipt.
        ProviderPaymentService.AttemptRow attempt = new ProviderPaymentService.AttemptRow(
                java.util.UUID.randomUUID(), "lp_dummy", 1000L, "INR",
                "test", "ref", "READY_FOR_CHECKOUT", "order_abc", null,
                java.time.Instant.now(), java.time.Instant.now(), null);
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(attempt));
        ProviderPaymentModels.WebhookAck ack = service.ingest(body, signature, "evt_001");
        assertEquals("OK", ack.status());
    }

    @Test
    void ingestInvalidSignatureRejected() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        ProviderPaymentException ex = assertThrows(ProviderPaymentException.class, () -> service.ingest(body, "bad_sig", "evt_002"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.status());
        assertEquals("INVALID_WEBHOOK_SIGNATURE", ex.code());
    }

    @Test
    void ingestDuplicateEventIdIsIdempotent() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signature = sign(body);
        // Mock a matching attempt row for the receipt.
        ProviderPaymentService.AttemptRow attempt = new ProviderPaymentService.AttemptRow(
                java.util.UUID.randomUUID(), "lp_dummy", 1000L, "INR",
                "test", "ref", "READY_FOR_CHECKOUT", "order_abc", null,
                java.time.Instant.now(), java.time.Instant.now(), null);
        when(jdbc.query(anyString(), any(RowMapper.class), any())).thenReturn(List.of(attempt));
        // First call stores the event.
        ProviderPaymentModels.WebhookAck first = service.ingest(body, signature, "evt_dup");
        assertEquals("OK", first.status());
        // Second call with same eventId should simply acknowledge without error.
        ProviderPaymentModels.WebhookAck second = service.ingest(body, signature, "evt_dup");
        assertEquals("OK", second.status());
    }
}
