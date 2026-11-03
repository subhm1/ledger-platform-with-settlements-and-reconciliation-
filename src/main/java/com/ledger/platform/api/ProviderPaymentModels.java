package com.ledger.platform.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProviderPaymentModels {
    private ProviderPaymentModels() { }

    public record CreateRequest(Long amountMinor, String currencyCode, String purpose, String businessReference) { }
    public record Checkout(UUID attemptId, String providerOrderId, String keyId, String amountMinor, String currencyCode) { }
    public record Attempt(
            UUID attemptId, String receipt, String amountMinor, String currencyCode,
            String purpose, String businessReference, String state,
            String providerOrderId, String providerPaymentId, Instant createdAt, Instant updatedAt) { }
    public record AttemptPage(List<Attempt> items, int page, int size, long totalElements, long totalPages,
                              boolean checkoutConfigured, String checkoutKeyId) {
        public AttemptPage { items = List.copyOf(items); }
    }
    public record Event(UUID eventId, String eventType, String previousState, String newState,
                        String safeDetail, Instant occurredAt) { }
    public record Detail(Attempt attempt, String accountingState, List<Event> events) {
        public Detail { events = List.copyOf(events); }
    }
    public record CreateResponse(Attempt attempt, Checkout checkout) { }
    public record CheckoutVerification(String paymentId, String orderId, String signature) { }
    public record WebhookAck(String status) { }

    /**
     * A durably persisted webhook event row, safe for operator inspection.
     * Secrets and raw payload bytes are never included in this response.
     */
    public record WebhookEvent(
            UUID id,
            String providerEventId,
            String eventType,
            String providerOrderId,
            String providerPaymentId,
            String processingState,
            int attemptCount,
            String safeErrorCode,
            Instant receivedAt,
            Instant processedAt) { }

    public record WebhookEventPage(List<WebhookEvent> items, int page, int size,
                                   long totalElements, long totalPages) {
        public WebhookEventPage { items = List.copyOf(items); }
    }
}
