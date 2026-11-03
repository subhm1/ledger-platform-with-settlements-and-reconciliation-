# Phase 6.2 Part 2 — Razorpay Test Mode collection backend

**Status: COMPLETE — NON-POSTING SCOPE.** This accepts the verified Test Mode backend and safe ambiguous-order recovery only. Captured payments are recorded as `CAPTURED_VERIFIED` and report `ACCOUNTING_GATE_PENDING`; no journal transaction or journal entries are created because the collection debit/credit accounts and accounting treatment have not been approved. This is not approval for live payments or payment-to-ledger posting.

## Implemented in this scope

- V5 adds `payment_attempts` and `payment_webhook_events`, constraints, lifecycle triggers, unique provider/idempotency identifiers, and limited `ledger_runtime` grants. V6 narrowly allows the guarded `ORDER_UNKNOWN` to `READY_FOR_CHECKOUT` transition only when a provider order ID is supplied. Both migrations were tested only in disposable Testcontainers PostgreSQL databases; no migration was applied to the review database.
- Authenticated payment-attempt APIs persist an idempotent local attempt before making a single Razorpay Test Mode order request. A timeout is recorded as `ORDER_UNKNOWN`; the system does not retry an ambiguous order creation automatically.
- Checkout callback signatures are checked server-side and payment state is refreshed from Razorpay's API before marking a payment captured. Browser callback data alone is not treated as proof of capture.
- The webhook API verifies HMAC over the exact raw body, persists minimized event IDs before acknowledgment, deduplicates event IDs in PostgreSQL, and uses `FOR UPDATE SKIP LOCKED` for inbox claims. Failures retry with bounded backoff or move to review; abandoned `PROCESSING` events can be reclaimed after the lease timeout.
- The operator-protected `POST /api/v1/payment-attempts/{attemptId}/recover-order` queries Razorpay Orders by the persisted unique receipt. It never repeats order creation. It associates an order only when exactly one exact-receipt result matches the local amount/currency and is `created` with zero attempts, zero amount paid, and the full amount due. Row locking, the unique provider-order constraint, and immutable identity/state triggers make concurrent/repeated recovery safe.
- Razorpay requests use the fixed `https://api.razorpay.com/v1/` base URL, require an `rzp_test_` key ID, use connect/request timeouts, sanitize provider errors, and enforce a 256 KiB response limit while streaming. The webhook request body is also limited to 256 KiB.
- Only the webhook POST route is unauthenticated at the application layer and it requires a valid Razorpay signature; `/api/**` remains operator protected. `.env.example` contains blank payment secret placeholders.
- Local environment variables are optional and blank in `.env.example`; missing configuration fails closed.

## Acceptance blockers and limits

1. Accounting owner approval is required for the exact debit/credit accounts and posting policy for captured collections. No chart-of-accounts or mapping is inferred by this implementation.
2. Until that approval, journal posting, journal linkage, and end-to-end collection acceptance remain intentionally absent. Captures can remain unposted.
3. Lookup may be inconclusive or return no order; those attempts remain `ORDER_UNKNOWN`. Multiple matches, identifier conflicts, mismatched amount/currency, or orders with payment activity move to `REVIEW_REQUIRED`. These cases require the documented manual investigation process; no API allows an operator to override review state.
4. The actual Razorpay sandbox was not exercised because approved Test Mode credentials were not available to this process. Fake-provider tests do not prove external connectivity or webhook delivery.
5. The local review stack is not an externally reachable webhook environment. Do not treat local inbox tests as proof of Razorpay webhook delivery configuration.

## Configuration

Set `LEDGER_RAZORPAY_KEY_ID` to an `rzp_test_` key ID, plus server-side `LEDGER_RAZORPAY_API_SECRET` and `LEDGER_RAZORPAY_WEBHOOK_SECRET`. Never expose the API or webhook secret to the frontend. Leave all three blank to keep collection APIs fail-closed. Provision the matching webhook URL and secret in the Razorpay Test Mode dashboard only when a reachable test environment is ready.

## Verification

Executed successfully on 2026-10-25 after the recovery correction.

Commands:
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=PaymentWorkflowTests,RazorpayHttpClientTests,LedgerPlatformApplicationTests' test
.\mvnw.cmd --batch-mode --no-transfer-progress verify
git diff --check
git status --short

The focused command passed **82 tests**: 59 LedgerPlatformApplicationTests, 20 PaymentWorkflowTests, and 3 RazorpayHttpClientTests; 0 failures, 0 errors, and 0 skipped. Full verify passed **89 tests**, including 7 SettlementCsvParserTests; 0 failures, 0 errors, and 0 skipped. All database-backed tests ran against disposable Testcontainers PostgreSQL databases. V1–V6 were applied only inside those disposable containers. The persistent review database and Docker volumes were not accessed or changed. No external Razorpay connectivity was tested.

## Failures found and repaired

1. LedgerPlatformApplicationTests.connectsToPostgresAndRunsFlyway now expects the current forward migration V6.
2. PaymentWorkflowTests.capturedWebhookIsPersistedThenVerifiedWithoutLedgerPosting uses isolated payment-attempt and webhook fixtures in its disposable Testcontainers database.
3. RazorpayHttpClient cancels oversized response streams at 256 KiB; RazorpayHttpClientTests.rejectsOversizedProviderResponseWhileReading verifies this.
4. Recovery previously treated every DataAccessException as a provider-order conflict and could downgrade an already recovered attempt. PaymentAttemptService now handles only DuplicateKeyException as a possible association conflict, performs a fresh locked read after failures, returns an already committed matching association idempotently, records a confirmed duplicate association only while still ORDER_UNKNOWN, and propagates unresolved infrastructure failures without changing state. PaymentWorkflowTests.transientDatabaseFailureDuringRecoveryPreservesUnknownAndPropagates and PaymentWorkflowTests.ambiguousCommitReturnsAlreadyCommittedRecoveryWithoutDowngrade cover transient and ambiguous commit outcomes. PaymentWorkflowTests.providerOrderAlreadyLinkedToAnotherAttemptIsNotReused covers a real conflicting order association.
5. PaymentWorkflowTests now independently reject attempted status, nonzero attempt count, nonzero amount paid, and incorrect amount due. LedgerPlatformApplicationTests.recoverOrderRequiresOperatorAuthentication checks anonymous and non-operator POSTs are rejected.

## Security and external-provider review

- Reviewed `RazorpayProperties`, `RazorpayHttpClient`, `PaymentAttemptController`, `RazorpayWebhookController`, `SecurityConfiguration`, V2/V5/V6 privileges and state guards, Compose files, and `.env.example`. The recovery route is covered by the existing `/api/**` operator authorization. No ignored environment file was opened and no secret values were printed.
- No Razorpay Test Mode environment variables were available to this process, so no external API request or webhook delivery was attempted. Test credentials, dashboard setup, and external connectivity remain unverified. No live payment path or credentials were used.
- Payment secrets are configured server-side. API responses expose only the public key ID; provider errors are sanitized. The local stack binds UI and database host ports to loopback; the review Compose override publishes no PostgreSQL host port. Backend/database services communicate on the Compose network.
- The local HTTP client tests cover successful response parsing and bounded oversized-response rejection. They do not establish real Razorpay behavior or dashboard configuration.

## Database trust boundary

V5 grants ledger_runtime direct UPDATE privileges on payment-attempt lifecycle fields, and V6's trigger enforces allowed state transitions, immutable identity, and a non-null provider order ID for ORDER_UNKNOWN to READY_FOR_CHECKOUT. The database trigger cannot independently prove the Razorpay receipt, amount, currency, status, or attempt data; those checks are performed by the application before it writes the association.

ledger_runtime is the application's database credential and is intended to be used by the backend service, not handed to operators or used for ad hoc SQL. Direct SQL fabrication of a provider order ID is therefore outside the accepted operator/API threat model, but remains possible to a party that obtains the runtime database credential or can execute arbitrary SQL as that role. The role must remain secret, restricted to the backend, and protected from SQL injection. No V1–V5 migration was changed, and no additional migration was necessary for this scoped recovery correction. This assessment does not claim that the database role can defend against compromise of the application credential itself.

## Ambiguous-order recovery

An attempt enters `ORDER_UNKNOWN` when order creation returns an exception or an ambiguous provider outcome. The service never automatically repeats the order-creation POST because Razorpay may have created the order even if the response was lost.

Recovery calls Razorpay `GET /v1/orders?receipt={receipt}&count=100`, an Orders API query documented to retrieve orders filtered by receipt ([Razorpay Fetch All Orders](https://razorpay.com/docs/api/orders/fetch-all/)). The response is not trusted solely because the API filtered it: the application requires one result with an exact receipt match, the local amount/currency, a valid `order_...` ID, `created` status, zero attempts, zero paid amount, and the full amount due. A successful recovery reuses this provider order and does not issue another create request. The provider order's existence or `paid` status alone never marks a payment captured.

No exact match or an incomplete lookup leaves the attempt `ORDER_UNKNOWN`. Multiple exact matches, mismatched order details, duplicate provider IDs, or any order showing payment activity goes to `REVIEW_REQUIRED` without fabricating a payment association. Repeated/concurrent recovery for the same order returns or preserves the one persisted association. The endpoint is authenticated as an operator under `/api/**`.

For an unresolved attempt, an operator should retrieve the attempt by its local UUID, record its receipt/amount/currency/business reference, and inspect that receipt in the matching Razorpay Test Mode account. Do not create another order or alter the database manually. If the provider list lookup later returns exactly one eligible order, invoke the recovery endpoint. If no order appears, the provider is unavailable, multiple orders exist, details differ, or any payment activity is present, keep the attempt unresolved/review-required and escalate with the provider response and local attempt identifiers. There is no automated/manual endpoint to override review state. A separate payment ID must still pass the existing callback signature and trusted payment lookup before any `CAPTURED_VERIFIED` state.

## Acceptance decision

**COMPLETE — NON-POSTING SCOPE.** The receipt-based, no-retry recovery path and recovery failure handling are implemented and covered with fake provider responses, HTTP stubs, and disposable database tests. An absent or ambiguous provider result remains unresolved/review-required by design and follows the manual investigation procedure above. Real Razorpay Test Mode connectivity and dashboard webhook delivery remain unverified because approved credentials and a reachable webhook environment were unavailable. Ledger posting remains gated until the accounting owner approves the exact debit/credit accounts and policy. No Payments UI or Part 3 work was started. Part 2 is ready for Part 3 within the non-posting scope; external Razorpay validation and accounting approval remain separate gates.
