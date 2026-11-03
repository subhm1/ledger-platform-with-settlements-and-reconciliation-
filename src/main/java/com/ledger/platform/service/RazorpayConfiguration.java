package com.ledger.platform.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class RazorpayConfiguration {
    private final String keyId;
    private final String apiSecret;
    private final String webhookSecret;

    public RazorpayConfiguration(
            @Value("${ledger.razorpay.key-id:}") String keyId,
            @Value("${ledger.razorpay.api-secret:}") String apiSecret,
            @Value("${ledger.razorpay.webhook-secret:}") String webhookSecret) {
        this.keyId = keyId == null ? "" : keyId.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret;
        this.webhookSecret = webhookSecret == null ? "" : webhookSecret;
    }

    public boolean configured() {
        return keyId.startsWith("rzp_test_") && !apiSecret.isBlank();
    }

    public boolean webhookConfigured() {
        return !webhookSecret.isBlank();
    }

    public String keyId() { return keyId; }
    String apiSecret() { return apiSecret; }
    String webhookSecret() { return webhookSecret; }
}
