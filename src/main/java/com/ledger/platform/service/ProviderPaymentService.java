package com.ledger.platform.service;

import com.ledger.platform.api.ProviderPaymentModels;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public final class ProviderPaymentService {
    private static final String ATTEMPT_COLUMNS = "id, receipt, amount_minor, currency_code, purpose, business_reference, state, provider_order_id, provider_payment_id, created_at, updated_at";
    private static final RowMapper<AttemptRow> ATTEMPT_MAPPER = (rs, row) -> new AttemptRow(
            rs.getObject("id", UUID.class), rs.getString("receipt"), rs.getLong("amount_minor"),
            rs.getString("currency_code"), rs.getString("purpose"), rs.getString("business_reference"),
            rs.getString("state"), rs.getString("provider_order_id"), rs.getString("provider_payment_id"),
            instant(rs, "created_at"), instant(rs, "updated_at"));
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final RazorpayConfiguration configuration;
    private final RazorpayGateway gateway;

    public ProviderPaymentService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                                  RazorpayConfiguration configuration, RazorpayGateway gateway) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.configuration = configuration;
        this.gateway = gateway;
    }

    public Created create(ProviderPaymentModels.CreateRequest request, String idempotencyKey, String operator) {
        requireConfigured();
        validate(request, idempotencyKey, operator);
        String purpose = request.purpose().trim();
        String reference = request.businessReference().trim();
        String scope = "provider-payment:" + sha256(operator.trim());
        String fingerprint = sha256(request.amountMinor() + "\nINR\n" + purpose + "\n" + reference);
        UUID attemptId = UUID.randomUUID();
        String receipt = "lp_" + attemptId.toString().replace("-", "");

        Integer inserted = transaction.execute(status -> {
            Integer currencyCount = jdbc.queryForObject(
                    "SELECT count(*) FROM public.currencies WHERE code = 'INR' AND enabled = TRUE", Integer.class);
            if (currencyCount == null || currencyCount != 1) {
                throw new ProviderPaymentException(HttpStatus.UNPROCESSABLE_ENTITY, "CURRENCY_NOT_ENABLED",
                        "INR is not enabled for provider payments in this environment.");
            }
            int count = jdbc.update("""
                    INSERT INTO public.payment_attempts
                        (id, idempotency_scope, idempotency_key, request_fingerprint, receipt,
                         amount_minor, currency_code, purpose, business_reference, state)
                    VALUES (?, ?, ?, ?, ?, ?, 'INR', ?, ?, 'ORDER_PENDING')
                    ON CONFLICT (idempotency_scope, idempotency_key) DO NOTHING
                    """, attemptId, scope, idempotencyKey.trim(), fingerprint, receipt,
                    request.amountMinor(), purpose, reference);
            if (count == 1) event(attemptId, "ATTEMPT_CREATED", null, "ORDER_PENDING", "Request recorded.");
            return count;
        });

        if (inserted == null || inserted == 0) {
            AttemptRow existing = findByIdempotency(scope, idempotencyKey.trim());
            if (existing == null) throw internalFailure();
            if (!existing.fingerprint().equals(fingerprint)) {
                throw new ProviderPaymentException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "This retry key was already used for different payment details.");
            }
            return new Created(createResponse(existing), false);
        }

        AttemptRow pending = find(attemptId, false);
        try {
            Map<String, Object> order = gateway.createOrder(receipt, pending.amountMinor(), "INR", reference);
            String orderId = text(order, "id");
            boolean valid = orderId.startsWith("order_")
                    && receipt.equals(text(order, "receipt"))
                    && numeric(order, "amount") == pending.amountMinor()
                    && "INR".equals(text(order, "currency"))
                    && "created".equals(text(order, "status"))
                    && numeric(order, "attempts") == 0
                    && numeric(order, "amount_paid") == 0
                    && numeric(order, "amount_due") == pending.amountMinor();
            transition(attemptId, valid ? "READY_FOR_CHECKOUT" : "REVIEW_REQUIRED",
                    orderId.isBlank() ? null : orderId, null,
                    valid ? null : "PROVIDER_ORDER_MISMATCH",
                    valid ? "ORDER_CREATED" : "ORDER_RESPONSE_REVIEW",
                    valid ? "Provider order identity and amount verified." : "Provider order response requires investigation.");
        } catch (RestClientException | DataAccessException exception) {
            transition(attemptId, "ORDER_UNKNOWN", null, null, "PROVIDER_ORDER_UNKNOWN",
                    "ORDER_OUTCOME_UNKNOWN", "Order creation outcome is unknown; do not create another order.");
        } catch (ProviderPaymentException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            transition(attemptId, "ORDER_UNKNOWN", null, null, "PROVIDER_ORDER_UNKNOWN",
                    "ORDER_OUTCOME_UNKNOWN", "Order creation outcome is unknown; do not create another order.");
        }
        return new Created(createResponse(find(attemptId, false)), true);
    }

    public ProviderPaymentModels.AttemptPage list(int page, int size) {
        ReadPageRequest request = ReadPageRequest.validate(page, size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM public.payment_attempts", Long.class);
        List<AttemptRow> rows = jdbc.query("SELECT " + ATTEMPT_COLUMNS + " FROM public.payment_attempts ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                ATTEMPT_MAPPER, request.size(), request.offset());
        long totalElements = total == null ? 0 : total;
        long pages = totalElements / size + (totalElements % size == 0 ? 0 : 1);
        return new ProviderPaymentModels.AttemptPage(rows.stream().map(this::toApi).toList(), page, size, totalElements, pages,
                configuration.configured(), configuration.configured() ? configuration.keyId() : "");
    }

    public ProviderPaymentModels.Detail detail(UUID attemptId) {
        AttemptRow attempt = find(attemptId, false);
        List<ProviderPaymentModels.Event> events = jdbc.query("""
                SELECT id, event_type, previous_state, new_state, safe_detail, occurred_at
                FROM public.payment_attempt_events WHERE attempt_id = ? ORDER BY occurred_at, id
                """, (rs, row) -> new ProviderPaymentModels.Event(
                rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("previous_state"),
                rs.getString("new_state"), rs.getString("safe_detail"), instant(rs, "occurred_at")), attempt.id());
        return new ProviderPaymentModels.Detail(toApi(attempt), accountingState(attempt.state()), events);
    }

    public ProviderPaymentModels.Detail recoverOrder(UUID attemptId) {
        requireConfigured();
        AttemptRow attempt = find(attemptId, false);
        if (!List.of("ORDER_UNKNOWN", "ORDER_PENDING").contains(attempt.state())) {
            throw badRequest("ORDER_NOT_UNKNOWN", "Only an unknown order outcome can be recovered.");
        }
        if ("ORDER_PENDING".equals(attempt.state()) && attempt.updatedAt().isAfter(java.time.Instant.now().minusSeconds(30))) {
            throw new ProviderPaymentException(HttpStatus.CONFLICT, "ORDER_STILL_PENDING",
                    "The order request may still be in progress. Retry recovery in a few seconds.");
        }
        List<Map<String, Object>> matches;
        try {
            matches = gateway.findOrdersByReceipt(attempt.receipt());
        } catch (RestClientException exception) {
            throw new ProviderPaymentException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_LOOKUP_UNAVAILABLE",
                    "The provider order could not be checked. Retry recovery later.");
        }
        if (matches.isEmpty()) {
            if ("ORDER_PENDING".equals(attempt.state())) transition(attemptId, "ORDER_UNKNOWN", null, null, "PROVIDER_ORDER_UNKNOWN",
                    "ORDER_OUTCOME_UNKNOWN", "No order was found yet; repeat recovery before creating another order.");
            return detail(attemptId);
        }
        if (matches.size() != 1) {
            transition(attemptId, "REVIEW_REQUIRED", null, null, "MULTIPLE_RECEIPT_MATCHES",
                    "ORDER_RECOVERY_REVIEW", "Multiple provider orders matched this unique receipt.");
            return detail(attemptId);
        }
        Map<String, Object> order = matches.getFirst();
        String orderId = text(order, "id");
        boolean valid = orderId.startsWith("order_")
                && attempt.receipt().equals(text(order, "receipt"))
                && numeric(order, "amount") == attempt.amountMinor()
                && "INR".equals(text(order, "currency"))
                && "created".equals(text(order, "status"))
                && numeric(order, "attempts") == 0
                && numeric(order, "amount_paid") == 0
                && numeric(order, "amount_due") == attempt.amountMinor();
        transition(attemptId, valid ? "READY_FOR_CHECKOUT" : "REVIEW_REQUIRED",
                orderId.isBlank() ? null : orderId, null,
                valid ? null : "RECOVERED_ORDER_MISMATCH",
                valid ? "ORDER_RECOVERED" : "ORDER_RECOVERY_REVIEW",
                valid ? "Existing order matched receipt, amount, currency, and unused state."
                        : "Existing order did not satisfy safe checkout recovery rules.");
        return detail(attemptId);
    }

    public ProviderPaymentModels.Detail verifyCheckout(UUID attemptId,
            ProviderPaymentModels.CheckoutVerification request) {
        requireConfigured();
        if (request == null || blank(request.orderId()) || request.orderId().length() > 80
                || (request.paymentId() != null && request.paymentId().length() > 80)
                || (request.signature() != null && request.signature().length() > 128)) {
            throw badRequest("INVALID_CHECKOUT_RESPONSE", "The Checkout response is incomplete.");
        }
        AttemptRow attempt = find(attemptId, false);
        if (!request.orderId().equals(attempt.providerOrderId())) {
            throw badRequest("ORDER_ID_MISMATCH", "The payment response does not match this attempt.");
        }

        if (blank(request.paymentId())) {
            if (!List.of("READY_FOR_CHECKOUT", "PAYMENT_PENDING").contains(attempt.state())) {
                throw badRequest("ATTEMPT_NOT_VERIFIABLE", "This attempt is not in an active checkout state.");
            }
            transaction.executeWithoutResult(status ->
                    event(attemptId, "CHECKOUT_DISMISSED", attempt.state(), attempt.state(), "Checkout modal dismissed without payment."));
            return detail(attemptId);
        }

        boolean hasSignature = !blank(request.signature());
        if (hasSignature && !validSignature(request.orderId(), request.paymentId(), request.signature())) {
            throw new ProviderPaymentException(HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_SIGNATURE",
                    "The payment response signature could not be verified.");
        }
        if (request.paymentId().equals(attempt.providerPaymentId())
                && List.of("CAPTURED_VERIFIED", "FAILED").contains(attempt.state())) {
            return detail(attemptId);
        }
        if (!List.of("READY_FOR_CHECKOUT", "PAYMENT_PENDING", "AUTHORIZED", "FAILED", "REVIEW_REQUIRED").contains(attempt.state())) {
            throw badRequest("ATTEMPT_NOT_VERIFIABLE", "This attempt is not ready for payment verification.");
        }

        Map<String, Object> payment;
        Map<String, Object> order;
        try {
            payment = gateway.fetchPayment(request.paymentId());
            order = gateway.fetchOrder(request.orderId());
        } catch (org.springframework.web.client.HttpClientErrorException.NotFound exception) {
            throw badRequest("PROVIDER_PAYMENT_NOT_FOUND", "The provider payment could not be found for this order.");
        } catch (RestClientException exception) {
            transition(attemptId, "PAYMENT_PENDING", null, null, "PROVIDER_VERIFICATION_PENDING",
                    "VERIFICATION_PENDING", "Provider verification is pending; browser data is not proof of payment.");
            return detail(attemptId);
        }
        boolean identityMatches = request.orderId().equals(text(payment, "order_id"))
                && request.orderId().equals(text(order, "id"))
                && attempt.receipt().equals(text(order, "receipt"));
        boolean amountMatches = numeric(payment, "amount") == attempt.amountMinor()
                && numeric(order, "amount") == attempt.amountMinor()
                && numeric(order, "amount_paid") <= attempt.amountMinor()
                && numeric(order, "amount_due") >= 0
                && numeric(order, "amount_due") <= attempt.amountMinor()
                && numeric(order, "amount_paid") + numeric(order, "amount_due") == attempt.amountMinor()
                && numeric(order, "attempts") >= 1;
        boolean currencyMatches = "INR".equals(text(payment, "currency"))
                && "INR".equals(text(order, "currency"));
        if (!identityMatches || !amountMatches || !currencyMatches) {
            transition(attemptId, "REVIEW_REQUIRED", null, request.paymentId(), "PROVIDER_EVIDENCE_MISMATCH",
                    "VERIFICATION_REVIEW", "Provider order/payment identity, amount, or currency did not match the request.");
            return detail(attemptId);
        }

        String paymentStatus = text(payment, "status");
        String next;
        String safeDetail;
        String safeErrorCode = null;
        String eventType = "PAYMENT_VERIFIED";

        if ("failed".equals(paymentStatus)) {
            next = "FAILED";
            safeErrorCode = "PROVIDER_PAYMENT_FAILED";
            eventType = "PAYMENT_FAILED";
            safeDetail = "Provider reports a failed payment attempt.";
        } else if ("created".equals(paymentStatus) || "pending".equals(paymentStatus)) {
            next = "PAYMENT_PENDING";
            safeErrorCode = "PROVIDER_PAYMENT_PENDING";
            eventType = "PAYMENT_UNRESOLVED";
            safeDetail = "Provider reports payment is created but not yet confirmed; outcome unresolved.";
        } else if ("captured".equals(paymentStatus)) {
            if (!hasSignature) {
                next = "REVIEW_REQUIRED";
                safeErrorCode = "SIGNATURE_MISSING_FOR_CAPTURED_PAYMENT";
                eventType = "VERIFICATION_REVIEW";
                safeDetail = "Provider reports captured payment but client provided no signature; operator review required.";
            } else if (Boolean.TRUE.equals(payment.get("captured"))
                    && "paid".equals(text(order, "status"))
                    && numeric(order, "amount_paid") == attempt.amountMinor()
                    && numeric(order, "amount_due") == 0) {
                next = "CAPTURED_VERIFIED";
                safeDetail = "Server verification confirmed captured payment and fully paid order.";
            } else {
                next = "REVIEW_REQUIRED";
                safeErrorCode = "PROVIDER_STATE_REVIEW";
                eventType = "VERIFICATION_REVIEW";
                safeDetail = "Provider returned inconsistent capture state; operator review required.";
            }
        } else if ("authorized".equals(paymentStatus)) {
            if (!hasSignature) {
                next = "REVIEW_REQUIRED";
                safeErrorCode = "SIGNATURE_MISSING_FOR_AUTHORIZED_PAYMENT";
                eventType = "VERIFICATION_REVIEW";
                safeDetail = "Provider reports authorized payment but client provided no signature; operator review required.";
            } else if (!Boolean.TRUE.equals(payment.get("captured"))
                    && "attempted".equals(text(order, "status"))
                    && numeric(order, "amount_paid") == 0 && numeric(order, "amount_due") == attempt.amountMinor()) {
                next = "AUTHORIZED";
                safeDetail = "Provider reports authorization; capture is not confirmed.";
            } else {
                next = "REVIEW_REQUIRED";
                safeErrorCode = "PROVIDER_STATE_REVIEW";
                eventType = "VERIFICATION_REVIEW";
                safeDetail = "Provider returned inconsistent authorization state; operator review required.";
            }
        } else {
            next = "REVIEW_REQUIRED";
            safeErrorCode = "PROVIDER_STATE_REVIEW";
            eventType = "VERIFICATION_REVIEW";
            safeDetail = "Provider returned unexpected payment status: " + paymentStatus;
        }

        transition(attemptId, next, null, request.paymentId(), safeErrorCode, eventType, safeDetail);
        return detail(attemptId);
    }

    // ---- Webhook ingestion ----

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Supported Razorpay webhook event types.
     * Events not in this set are persisted as REVIEW_REQUIRED and acknowledged without state change.
     */
    private static final Set<String> SUPPORTED_WEBHOOK_EVENTS = Set.of(
            "payment.authorized", "payment.captured", "payment.failed", "order.paid");

    /**
     * Ingests a Razorpay webhook event.
     * <p>
     * Contract (Phase 6.3.2):
     * <ol>
     *   <li>Reject missing body or signature with 400.</li>
     *   <li>Validate HMAC-SHA256 signature against raw body; reject invalid signatures with 400.
     *       Signature failure MUST NOT mutate payment or accounting state.</li>
     *   <li>Durably persist the accepted event in {@code payment_webhook_events} before processing.
     *       The unique constraint on {@code (provider, environment, provider_event_id)} is the
     *       authoritative dedup anchor; duplicate delivery is a DB-level no-op.</li>
     *   <li>If the event was a duplicate (DB conflict), return OK immediately.</li>
     *   <li>Validate supported event types; persist unsupported events as REVIEW_REQUIRED.</li>
     *   <li>Apply the corresponding payment state transition idempotently.</li>
     *   <li>Mark the persisted event PROCESSED or REVIEW_REQUIRED.</li>
     *   <li>Acknowledge unconditionally so Razorpay stops retrying.</li>
     * </ol>
     * Secrets are never logged or included in responses.
     */
    public ProviderPaymentModels.WebhookAck ingest(byte[] body, String signature, String eventId) {
        if (!configuration.webhookConfigured()) {
            throw new ProviderPaymentException(HttpStatus.SERVICE_UNAVAILABLE, "WEBHOOK_NOT_CONFIGURED",
                    "Webhook secret is not configured.");
        }
        if (body == null || body.length == 0 || blank(signature)) {
            throw badRequest("INVALID_WEBHOOK_PAYLOAD", "Webhook body or signature is missing.");
        }
        // Step 1 — Verify signature against raw bytes. No state change on failure.
        if (!validWebhookSignature(body, signature)) {
            throw badRequest("INVALID_WEBHOOK_SIGNATURE", "The webhook signature could not be verified.");
        }

        // Step 2 — Parse payload fields needed for persistence (best-effort; safe defaults on parse error).
        String parsedEventType = "";
        String parsedOrderId = null;
        String parsedPaymentId = null;
        String parsedReceipt = "";
        Long parsedAmountMinor = null;
        String parsedCurrencyCode = null;
        JsonNode parsedRoot = null;
        try {
            parsedRoot = MAPPER.readTree(body);
            parsedEventType = parsedRoot.path("event").asText("");
            JsonNode payloadNode = parsedRoot.path("payload");
            JsonNode paymentEntity = payloadNode.path("payment").path("entity");
            JsonNode orderEntity = payloadNode.path("order").path("entity");
            parsedOrderId = paymentEntity.path("order_id").asText(orderEntity.path("id").asText(null));
            parsedPaymentId = paymentEntity.path("id").asText(null);
            parsedReceipt = orderEntity.path("receipt").asText("");
            long amt = paymentEntity.path("amount").asLong(0L);
            if (amt <= 0) amt = orderEntity.path("amount").asLong(0L);
            if (amt > 0) parsedAmountMinor = amt;
            String curr = paymentEntity.path("currency").asText(orderEntity.path("currency").asText(""));
            if (!curr.isBlank()) parsedCurrencyCode = curr.trim().toUpperCase();
            if (parsedOrderId != null && parsedOrderId.isBlank()) parsedOrderId = null;
            if (parsedPaymentId != null && parsedPaymentId.isBlank()) parsedPaymentId = null;
        } catch (Exception ignored) {
            // Parsing failed; persist with empty event type and no IDs.
        }

        // Use a stable event ID for dedup; fall back to a content hash if the header is absent.
        final String stableEventId = (eventId != null && !eventId.isBlank())
                ? eventId
                : sha256(new String(body, StandardCharsets.UTF_8));
        final String finalEventType = parsedEventType;
        final String finalOrderId = parsedOrderId;
        final String finalPaymentId = parsedPaymentId;
        final String finalReceipt = parsedReceipt;
        final Long finalAmountMinor = parsedAmountMinor;
        final String finalCurrencyCode = parsedCurrencyCode;
        final JsonNode finalRoot = parsedRoot;

        // Step 3 — Durably persist the event (dedup via unique constraint).
        final UUID[] webhookRowId = { null };
        transaction.executeWithoutResult(status -> {
            List<UUID> existing = jdbc.query(
                    "SELECT id FROM public.payment_webhook_events " +
                    "WHERE provider = 'RAZORPAY' AND environment = 'TEST' AND provider_event_id = ?",
                    (rs, row) -> rs.getObject("id", UUID.class), stableEventId);
            if (!existing.isEmpty()) {
                // Duplicate delivery — row already exists; return without processing.
                webhookRowId[0] = null;
                return;
            }
            UUID rowId = UUID.randomUUID();
            // Build a safe payload snapshot: strip any fields that could contain secrets.
            String safePayloadJson = buildSafePayloadJson(finalRoot, finalEventType, finalOrderId, finalPaymentId, finalReceipt, finalAmountMinor, finalCurrencyCode);
            jdbc.update("""
                    INSERT INTO public.payment_webhook_events
                        (id, provider, environment, provider_event_id, event_type,
                         provider_order_id, provider_payment_id, signature_verified,
                         processing_state, attempt_count, safe_payload_json,
                         amount_minor, currency_code)
                    VALUES (?, 'RAZORPAY', 'TEST', ?, ?, ?, ?, TRUE, 'RECEIVED', 0, ?::jsonb, ?, ?)
                    """, rowId, stableEventId,
                    finalEventType.isBlank() ? "UNKNOWN" : finalEventType,
                    finalOrderId, finalPaymentId,
                    safePayloadJson,
                    finalAmountMinor, finalCurrencyCode);
            webhookRowId[0] = rowId;
        });

        // Step 4 — If duplicate, acknowledge immediately.
        if (webhookRowId[0] == null) {
            return new ProviderPaymentModels.WebhookAck("OK");
        }
        final UUID rowId = webhookRowId[0];

        // Step 5 — Validate supported event type and apply state transition.
        boolean supported = SUPPORTED_WEBHOOK_EVENTS.contains(finalEventType);
        if (!supported) {
            markWebhookReviewRequired(rowId, "UNSUPPORTED_EVENT_TYPE");
            return new ProviderPaymentModels.WebhookAck("OK");
        }

        // Step 6 — Apply payment state transition.
        try {
            markWebhookProcessing(rowId);
            processWebhookEvent(finalEventType, finalOrderId, finalPaymentId, finalReceipt, rowId);
            markWebhookProcessed(rowId);
        } catch (Exception processingError) {
            // Record failure for later retry; never suppress the acknowledgement.
            try {
                markWebhookReviewRequired(rowId, "PROCESSING_ERROR");
            } catch (Exception ignored) { }
        }

        return new ProviderPaymentModels.WebhookAck("OK");
    }

    private void processWebhookEvent(String eventType, String orderId, String paymentId,
                                     String receipt, UUID webhookRowId) {
        if (receipt == null || !receipt.startsWith("lp_")) return;

        List<AttemptRow> rows = jdbc.query(
                "SELECT " + ATTEMPT_COLUMNS + ", request_fingerprint FROM public.payment_attempts WHERE receipt = ?",
                (rs, row) -> new AttemptRow(
                        rs.getObject("id", UUID.class), rs.getString("receipt"), rs.getLong("amount_minor"),
                        rs.getString("currency_code"), rs.getString("purpose"), rs.getString("business_reference"),
                        rs.getString("state"), rs.getString("provider_order_id"), rs.getString("provider_payment_id"),
                        instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("request_fingerprint")),
                receipt);
        if (rows.isEmpty()) {
            markWebhookReviewRequired(webhookRowId, "ATTEMPT_NOT_FOUND");
            return;
        }
        AttemptRow attempt = rows.getFirst();

        switch (eventType) {
            case "payment.captured" -> {
                // Transition to CAPTURED_VERIFIED if not already terminal.
                if (!List.of("CAPTURED_VERIFIED").contains(attempt.state())) {
                    transitionWithWebhook(attempt.id(), "CAPTURED_VERIFIED", orderId, paymentId,
                            null, "WEBHOOK_PAYMENT_CAPTURED",
                            "Payment captured and verified via provider webhook.", webhookRowId);
                } else {
                    // Idempotent: already captured; record observation only.
                    eventWithWebhook(attempt.id(), "WEBHOOK_PAYMENT_CAPTURED",
                            attempt.state(), attempt.state(),
                            "Duplicate capture webhook acknowledged; state unchanged.", webhookRowId);
                }
            }
            case "payment.authorized" -> {
                if (List.of("READY_FOR_CHECKOUT", "PAYMENT_PENDING").contains(attempt.state())) {
                    transitionWithWebhook(attempt.id(), "AUTHORIZED", orderId, paymentId,
                            null, "WEBHOOK_PAYMENT_AUTHORIZED",
                            "Payment authorized via provider webhook.", webhookRowId);
                } else {
                    eventWithWebhook(attempt.id(), "WEBHOOK_PAYMENT_AUTHORIZED",
                            attempt.state(), attempt.state(),
                            "Authorization webhook acknowledged; no state change for current state.", webhookRowId);
                }
            }
            case "payment.failed" -> {
                // Do not mark FAILED from webhook alone; record as PAYMENT_PENDING (unresolved).
                if (List.of("READY_FOR_CHECKOUT", "PAYMENT_PENDING").contains(attempt.state())) {
                    transitionWithWebhook(attempt.id(), "PAYMENT_PENDING", orderId, paymentId,
                            "PROVIDER_PAYMENT_PENDING", "WEBHOOK_PAYMENT_FAILED",
                            "Provider webhook reports payment failed; outcome requires verification.", webhookRowId);
                } else {
                    eventWithWebhook(attempt.id(), "WEBHOOK_PAYMENT_FAILED",
                            attempt.state(), attempt.state(),
                            "Failed payment webhook acknowledged; no state regression.", webhookRowId);
                }
            }
            case "order.paid" -> {
                // Observation only; cross-check is deferred.
                eventWithWebhook(attempt.id(), "WEBHOOK_ORDER_PAID",
                        attempt.state(), attempt.state(),
                        "Order paid webhook acknowledged.", webhookRowId);
            }
            default -> markWebhookReviewRequired(webhookRowId, "UNSUPPORTED_EVENT_TYPE");
        }
    }

    private void transitionWithWebhook(UUID id, String next, String orderId, String paymentId,
                                       String errorCode, String eventType, String details, UUID webhookRowId) {
        transaction.executeWithoutResult(status -> {
            AttemptRow old = find(id, true);
            String effectivePaymentId = (old.providerPaymentId() != null) ? old.providerPaymentId() : paymentId;
            String effectiveOrderId = (orderId != null) ? orderId : old.providerOrderId();
            if (old.state().equals(next)
                    && Objects.equals(old.providerOrderId(), effectiveOrderId)
                    && Objects.equals(old.providerPaymentId(), effectivePaymentId)) {
                // Already in this state — record observation with webhook link.
                eventWithWebhook(id, eventType, old.state(), next, details, webhookRowId);
                return;
            }
            int changed = jdbc.update("""
                    UPDATE public.payment_attempts SET state = ?,
                        provider_order_id = COALESCE(?, provider_order_id),
                        provider_payment_id = COALESCE(?, provider_payment_id),
                        safe_error_code = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, next, orderId, effectivePaymentId, errorCode, id);
            if (changed != 1) throw internalFailure();
            eventWithWebhook(id, eventType, old.state(), next, details, webhookRowId);
        });
    }

    private void eventWithWebhook(UUID attemptId, String type, String previous, String next,
                                  String details, UUID webhookRowId) {
        jdbc.update("""
                INSERT INTO public.payment_attempt_events
                    (attempt_id, event_type, previous_state, new_state, safe_detail, webhook_event_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """, attemptId, type, previous, next, details, webhookRowId);
    }

    private void markWebhookProcessing(UUID rowId) {
        jdbc.update("""
                UPDATE public.payment_webhook_events
                SET processing_state = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    processing_started_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, rowId);
    }

    private void markWebhookProcessed(UUID rowId) {
        jdbc.update("""
                UPDATE public.payment_webhook_events
                SET processing_state = 'PROCESSED', processed_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """, rowId);
    }

    private void markWebhookReviewRequired(UUID rowId, String errorCode) {
        jdbc.update("""
                UPDATE public.payment_webhook_events
                SET processing_state = 'REVIEW_REQUIRED', safe_error_code = ?
                WHERE id = ?
                """, errorCode, rowId);
    }

    /**
     * Builds a safe JSON snapshot of webhook payload fields for audit.
     * Only stable, non-secret identifiers are included.
     */
    private static String buildSafePayloadJson(JsonNode root, String eventType,
                                               String orderId, String paymentId, String receipt,
                                               Long amountMinor, String currencyCode) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
            node.put("event", eventType);
            if (orderId != null) node.put("order_id", orderId);
            if (paymentId != null) node.put("payment_id", paymentId);
            if (receipt != null && !receipt.isBlank()) node.put("receipt", receipt);
            if (amountMinor != null) node.put("amount_minor", amountMinor);
            if (currencyCode != null) node.put("currency_code", currencyCode);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Replays a previously ingested webhook event for audit or failure recovery.
     * Safely transitions state through PROCESSING to PROCESSED (or REVIEW_REQUIRED on error).
     */
    public ProviderPaymentModels.WebhookAck replayWebhookEvent(UUID webhookEventId) {
        List<WebhookReplayRow> rows = jdbc.query("""
                SELECT id, event_type, provider_order_id, provider_payment_id, processing_state,
                       safe_payload_json
                FROM public.payment_webhook_events WHERE id = ?
                """, (rs, row) -> new WebhookReplayRow(
                rs.getObject("id", UUID.class),
                rs.getString("event_type"),
                rs.getString("provider_order_id"),
                rs.getString("provider_payment_id"),
                rs.getString("processing_state"),
                rs.getString("safe_payload_json")),
                webhookEventId);
        if (rows.isEmpty()) {
            throw new ProviderPaymentException(HttpStatus.NOT_FOUND, "WEBHOOK_EVENT_NOT_FOUND",
                    "Webhook event was not found.");
        }
        WebhookReplayRow webhook = rows.getFirst();
        if ("PROCESSING".equals(webhook.processingState())) {
            throw new ProviderPaymentException(HttpStatus.CONFLICT, "WEBHOOK_ALREADY_PROCESSING",
                    "This webhook event is already being processed.");
        }
        if ("PROCESSED".equals(webhook.processingState())) {
            // Already processed; return idempotent acknowledgement without violating immutable trigger guard
            return new ProviderPaymentModels.WebhookAck("ALREADY_PROCESSED");
        }
        String receipt = "";
        try {
            if (webhook.payloadJson() != null && !webhook.payloadJson().isBlank()) {
                JsonNode node = MAPPER.readTree(webhook.payloadJson());
                receipt = node.path("receipt").asText("");
            }
        } catch (Exception ignored) { }

        markWebhookProcessing(webhookEventId);
        try {
            processWebhookEvent(webhook.eventType(), webhook.providerOrderId(),
                    webhook.providerPaymentId(), receipt, webhookEventId);
            markWebhookProcessed(webhookEventId);
        } catch (Exception processingError) {
            markWebhookReviewRequired(webhookEventId, "REPLAY_ERROR");
            throw new ProviderPaymentException(HttpStatus.INTERNAL_SERVER_ERROR, "REPLAY_FAILED",
                    "Webhook event replay failed: " + processingError.getMessage());
        }
        return new ProviderPaymentModels.WebhookAck("REPLAYED");
    }

    private record WebhookReplayRow(UUID id, String eventType, String providerOrderId,
                                    String providerPaymentId, String processingState, String payloadJson) { }

    /**
     * Lists persisted webhook events linked to the given payment attempt via
     * its {@code provider_order_id} or {@code provider_payment_id}.
     */
    public ProviderPaymentModels.WebhookEventPage listWebhookEvents(UUID attemptId, int page, int size) {
        ReadPageRequest req = ReadPageRequest.validate(page, size);
        AttemptRow attempt = find(attemptId, false);
        // Match on provider_order_id (always present once past ORDER_PENDING) or provider_payment_id.
        Long total = jdbc.queryForObject("""
                SELECT count(*) FROM public.payment_webhook_events
                WHERE (provider_order_id = ? AND ? IS NOT NULL)
                   OR (provider_payment_id = ? AND ? IS NOT NULL)
                """, Long.class,
                attempt.providerOrderId(), attempt.providerOrderId(),
                attempt.providerPaymentId(), attempt.providerPaymentId());
        List<ProviderPaymentModels.WebhookEvent> items = jdbc.query("""
                SELECT id, provider_event_id, event_type, provider_order_id, provider_payment_id,
                       processing_state, attempt_count, safe_error_code, received_at, processed_at
                FROM public.payment_webhook_events
                WHERE (provider_order_id = ? AND ? IS NOT NULL)
                   OR (provider_payment_id = ? AND ? IS NOT NULL)
                ORDER BY received_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (rs, row) -> new ProviderPaymentModels.WebhookEvent(
                        rs.getObject("id", UUID.class),
                        rs.getString("provider_event_id"),
                        rs.getString("event_type"),
                        rs.getString("provider_order_id"),
                        rs.getString("provider_payment_id"),
                        rs.getString("processing_state"),
                        rs.getInt("attempt_count"),
                        rs.getString("safe_error_code"),
                        instant(rs, "received_at"),
                        instant(rs, "processed_at")),
                attempt.providerOrderId(), attempt.providerOrderId(),
                attempt.providerPaymentId(), attempt.providerPaymentId(),
                req.size(), req.offset());
        long totalElements = total == null ? 0 : total;
        long pages = totalElements == 0 ? 0 : (totalElements / size + (totalElements % size == 0 ? 0 : 1));
        return new ProviderPaymentModels.WebhookEventPage(items, page, size, totalElements, pages);
    }

    private boolean validWebhookSignature(byte[] body, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(configuration.webhookSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);

            // 1. Try hex comparison (standard Razorpay signature format)
            String hexExpected = HexFormat.of().formatHex(expected);
            if (MessageDigest.isEqual(hexExpected.getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8))) {
                return true;
            }

            // 2. Try Base64 comparison
            byte[] actual = Base64.getDecoder().decode(signature.trim());
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private void validate(ProviderPaymentModels.CreateRequest request, String key, String operator) {
        if (request == null || request.amountMinor() == null || request.amountMinor() <= 0
                || request.amountMinor() > 100_000_000L) {
            throw badRequest("INVALID_AMOUNT", "Enter a positive amount of no more than INR 1,000,000.");
        }
        if (!"INR".equals(request.currencyCode())) {
            throw badRequest("INVALID_CURRENCY", "Provider Payments currently supports INR only.");
        }
        if (request.purpose() == null || request.purpose().trim().isEmpty() || request.purpose().trim().length() > 100) {
            throw badRequest("INVALID_PURPOSE", "Purpose must be between 1 and 100 characters.");
        }
        if (request.businessReference() == null || request.businessReference().trim().isEmpty()
                || request.businessReference().trim().length() > 150) {
            throw badRequest("INVALID_REFERENCE", "Business reference must be between 1 and 150 characters.");
        }
        if (key == null || key.isBlank() || key.trim().length() > 180) {
            throw badRequest("INVALID_IDEMPOTENCY_KEY", "A valid Idempotency-Key header is required.");
        }
        if (operator == null || operator.isBlank()) {
            throw new ProviderPaymentException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in before creating a provider payment.");
        }
    }

    private void requireConfigured() {
        if (!configuration.configured()) {
            throw new ProviderPaymentException(HttpStatus.SERVICE_UNAVAILABLE, "PROVIDER_NOT_CONFIGURED",
                    "Razorpay Test Mode is not configured.");
        }
    }

    private void transition(UUID id, String next, String orderId, String paymentId, String errorCode,
                            String eventType, String details) {
        transaction.executeWithoutResult(status -> {
            AttemptRow old = find(id, true);
            String effectivePaymentId = (old.providerPaymentId() != null) ? old.providerPaymentId() : paymentId;
            String effectiveOrderId = (orderId != null) ? orderId : old.providerOrderId();
            if (old.state().equals(next)
                    && Objects.equals(old.providerOrderId(), effectiveOrderId)
                    && Objects.equals(old.providerPaymentId(), effectivePaymentId)) {
                return;
            }
            int changed = jdbc.update("""
                    UPDATE public.payment_attempts SET state = ?,
                        provider_order_id = COALESCE(?, provider_order_id),
                        provider_payment_id = COALESCE(?, provider_payment_id),
                        safe_error_code = ?, version = version + 1, updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """, next, orderId, effectivePaymentId, errorCode, id);
            if (changed != 1) throw internalFailure();
            event(id, eventType, old.state(), next, details);
        });
    }

    private void event(UUID id, String type, String previous, String next, String details) {
        jdbc.update("""
                INSERT INTO public.payment_attempt_events
                    (attempt_id, event_type, previous_state, new_state, safe_detail)
                VALUES (?, ?, ?, ?, ?)
                """, id, type, previous, next, details);
    }

    private AttemptRow find(UUID id, boolean lock) {
        List<AttemptRow> rows = jdbc.query("SELECT " + ATTEMPT_COLUMNS + ", request_fingerprint FROM public.payment_attempts WHERE id = ?"
                + (lock ? " FOR UPDATE" : ""), (rs, row) -> new AttemptRow(
                rs.getObject("id", UUID.class), rs.getString("receipt"), rs.getLong("amount_minor"),
                rs.getString("currency_code"), rs.getString("purpose"), rs.getString("business_reference"),
                rs.getString("state"), rs.getString("provider_order_id"), rs.getString("provider_payment_id"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getString("request_fingerprint")), id);
        if (rows.isEmpty()) throw new ProviderPaymentException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Payment attempt not found.");
        return rows.getFirst();
    }

    private AttemptRow findByIdempotency(String scope, String key) {
        List<AttemptRow> rows = jdbc.query("SELECT " + ATTEMPT_COLUMNS + ", request_fingerprint FROM public.payment_attempts WHERE idempotency_scope = ? AND idempotency_key = ?",
                (rs, row) -> new AttemptRow(rs.getObject("id", UUID.class), rs.getString("receipt"), rs.getLong("amount_minor"),
                        rs.getString("currency_code"), rs.getString("purpose"), rs.getString("business_reference"), rs.getString("state"),
                        rs.getString("provider_order_id"), rs.getString("provider_payment_id"), instant(rs, "created_at"), instant(rs, "updated_at"),
                        rs.getString("request_fingerprint")), scope, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ProviderPaymentModels.CreateResponse createResponse(AttemptRow row) {
        ProviderPaymentModels.Checkout checkout = "READY_FOR_CHECKOUT".equals(row.state())
                ? new ProviderPaymentModels.Checkout(row.id(), row.providerOrderId(), configuration.keyId(),
                        Long.toString(row.amountMinor()), row.currencyCode()) : null;
        return new ProviderPaymentModels.CreateResponse(toApi(row), checkout);
    }

    private ProviderPaymentModels.Attempt toApi(AttemptRow row) {
        return new ProviderPaymentModels.Attempt(row.id(), row.receipt(), Long.toString(row.amountMinor()), row.currencyCode(),
                row.purpose(), row.businessReference(), row.state(), row.providerOrderId(), row.providerPaymentId(), row.createdAt(), row.updatedAt());
    }

    private static String accountingState(String state) {
        return "CAPTURED_VERIFIED".equals(state) ? "ACCOUNTING_GATE_PENDING" : "NOT_CAPTURED";
    }

    private boolean validSignature(String orderId, String paymentId, String signature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(configuration.apiSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8));
            byte[] actual = HexFormat.of().parseHex(signature);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception exception) {
            return false;
        }
    }

    private static String sha256(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private static String text(Map<String, Object> value, String key) {
        Object result = value == null ? null : value.get(key);
        return result instanceof String text ? text : "";
    }

    private static long numeric(Map<String, Object> value, String key) {
        Object result = value == null ? null : value.get(key);
        return result instanceof Number number ? number.longValue() : -1;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static java.time.Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
    private static ProviderPaymentException badRequest(String code, String message) {
        return new ProviderPaymentException(HttpStatus.BAD_REQUEST, code, message);
    }
    private static ProviderPaymentException internalFailure() {
        return new ProviderPaymentException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An internal error occurred.");
    }

    public record Created(ProviderPaymentModels.CreateResponse response, boolean created) { }
    /* package */ record AttemptRow(UUID id, String receipt, long amountMinor, String currencyCode, String purpose,
                              String businessReference, String state, String providerOrderId, String providerPaymentId,
                              java.time.Instant createdAt, java.time.Instant updatedAt, String fingerprint) {
        private AttemptRow(UUID id, String receipt, long amountMinor, String currencyCode, String purpose,
                           String businessReference, String state, String providerOrderId, String providerPaymentId,
                           java.time.Instant createdAt, java.time.Instant updatedAt) {
            this(id, receipt, amountMinor, currencyCode, purpose, businessReference, state, providerOrderId,
                    providerPaymentId, createdAt, updatedAt, "");
        }
    }
}
