package com.ledger.platform.api;

import com.ledger.platform.service.ProviderPaymentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/razorpay")
public final class RazorpayWebhookController {
    private final ProviderPaymentService service;

    public RazorpayWebhookController(ProviderPaymentService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ProviderPaymentModels.WebhookAck ingest(
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
            @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId,
            @RequestBody byte[] body) {
        return service.ingest(body == null ? new byte[0] : body, signature, eventId);
    }
}
