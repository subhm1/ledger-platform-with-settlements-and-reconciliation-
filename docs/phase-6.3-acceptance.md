# Phase 6.3 — Webhook Delivery and Reconciliation Integration: Acceptance Record

**Status: ACCEPTED for local review environment.** (Accounting approval and production deployment remain outstanding).

---

## 1. Executive Summary & Scope

Phase 6.3 establishes reliable ingestion and processing of Razorpay Test Mode webhooks and integrates provider evidence directly into the reconciliation engine without permitting ledger posting.

All work strictly adheres to the mandatory engineering guidelines:
- **Test Mode Only**: Exclusively Razorpay Test Mode (`TEST` environment, `RAZORPAY` provider); no real payments, payouts, or refunds.
- **Accounting Gate Maintained**: All captured provider payments remain `ACCOUNTING_GATE_PENDING`. Neither webhook ingestion nor reconciliation runs insert or alter records in `journal_transactions` or `journal_entries`.
- **Database & Migration Integrity**: Applied Flyway migrations (V1–V9) remained untouched. New additive migrations V10 and V11 were applied cleanly. The persistent review PostgreSQL database volume was preserved without recreation, truncation, or data loss.
- **Durable Deduplication & Immutability**: Enforced via PostgreSQL unique constraints and immutable trigger guards.
- **Zero Ledger Mutation**: Webhooks and provider reconciliation produce audit history without posting to financial accounts.

---

## 2. Implemented Sub-Phases & Contracts

### 6.3.1 — Scope and Contracts ([`docs/phase-6.3-scope.md`](file:///d:/01_MQ/0%20Projects/Fin_Projects/ledger-platform/docs/phase-6.3-scope.md))
- Defined supported Razorpay webhook event types: `payment.authorized`, `payment.captured`, `payment.failed`, and `order.paid`.
- Documented forward-only state progression rules preventing regressions from late or duplicate events.
- Defined deterministic matching classifications: `MATCHED`, `UNMATCHED_INTERNAL`, `UNMATCHED_PROVIDER`, `DUPLICATE`, and `CONFLICTING`.
- Documented explicit out-of-scope items: live payments, public deployment, manual conflict resolution, and ledger posting.

### 6.3.2 — Durable Webhook Intake and Verification
- **Migration V10** ([`V10__webhook_durable_ingestion.sql`](file:///d:/01_MQ/0%20Projects/Fin_Projects/ledger-platform/src/main/resources/db/migration/V10__webhook_durable_ingestion.sql)):
  - Added `safe_payload_json TEXT` to `public.payment_webhook_events` for non-sensitive audit logging.
  - Added foreign key `webhook_event_id` to `public.payment_attempt_events`.
- **Raw Signature Verification**:
  - Implemented HMAC-SHA256 signature verification in `ProviderPaymentService.ingest()` supporting both hex digest (official Razorpay standard) and Base64 encoding.
  - Uses constant-time `MessageDigest.isEqual` to prevent timing attacks.
  - Missing or invalid signatures reject immediately with HTTP 400 (`INVALID_WEBHOOK_PAYLOAD` / `INVALID_WEBHOOK_SIGNATURE`) with zero database writes.
- **Durable Ingestion**:
  - Valid payloads are durably inserted into `payment_webhook_events` with `signature_verified = TRUE` before attempting any state transitions.

### 6.3.3 — Idempotent Event Processing & Deduplication
- **Authoritative Database Dedup**:
  - Relies on PostgreSQL unique constraint `ON CONFLICT (provider, environment, provider_event_id) DO NOTHING`.
  - Duplicate deliveries return HTTP 200 without mutating the existing row or re-applying lifecycle transitions.
- **Forward-Only Payment State Protection**:
  - Out-of-order events (e.g. late `payment.authorized` or `payment.failed` delivered after `payment.captured`) are safely recorded as observations linked by `webhook_event_id` without regressing the attempt state from `CAPTURED_VERIFIED`.
- **Safe Replay Endpoint**:
  - Added `POST /api/v1/provider-payments/webhook-events/{webhookEventId}/replay`.
  - Safely respects the database lifecycle trigger `guard_payment_webhook_lifecycle()`. Events already `PROCESSED` return idempotent acknowledgement (`ALREADY_PROCESSED`) without trigger violation; events in `RETRY_WAIT` or `RECEIVED` transition through `PROCESSING -> PROCESSED`.

### 6.3.4 — Reconciliation Integration
- **Migration V11** ([`V11__provider_payment_reconciliation.sql`](file:///d:/01_MQ/0%20Projects/Fin_Projects/ledger-platform/src/main/resources/db/migration/V11__provider_payment_reconciliation.sql)):
  - Added `amount_minor` and `currency_code` columns to `payment_webhook_events`.
  - Created tables `provider_payment_reconciliation_runs` and `provider_payment_reconciliation_results`.
  - Created database trigger `reject_provider_reconciliation_mutation()` preventing any update or deletion of historical runs and results.
- **Deterministic Reconciliation Service** ([`ProviderPaymentReconciliationService.java`](file:///d:/01_MQ/0%20Projects/Fin_Projects/ledger-platform/src/main/java/com/ledger/platform/service/ProviderPaymentReconciliationService.java)):
  - Compares internal `CAPTURED_VERIFIED` attempts against external `payment.captured` webhook evidence.
  - Classifies outcomes deterministically:
    - `MATCHED`: Internal attempt and provider evidence agree on ID, amount, and currency.
    - `UNMATCHED_INTERNAL`: Captured internal attempt missing provider evidence.
    - `UNMATCHED_PROVIDER`: Provider capture event missing matching internal attempt.
    - `DUPLICATE`: Multiple provider capture events for the same payment identifier.
    - `CONFLICTING`: Matching identifiers with amount or currency mismatch.
- **Endpoints**:
  - `POST /api/v1/provider-payments/reconciliation-runs` (returns HTTP 201 Created)
  - `GET /api/v1/provider-payments/reconciliation-runs` (paginated)
  - `GET /api/v1/provider-payments/reconciliation-runs/{runId}`
  - `GET /api/v1/provider-payments/reconciliation-runs/{runId}/results` (filterable by outcome)

### 6.3.5 — API and Operations UI Behavior
- **Operations UI Console**:
  - Integrated `ProviderReconciliationView` into `ReconciliationConsole` with a top-level tab selector (**Settlement batches** vs **Provider payments**).
  - Displays recorded reconciliation runs with summary statistics and active run selection.
  - Displays classification outcomes table with filter dropdown and direct entity ID traceability.
  - Added "Run reconciliation" button triggering execution against real database records.
- **Webhook Evidence Section**:
  - Added to `ProviderPaymentsConsole` beneath the payment stepper.
  - Displays persisted webhook events with state badges (`PROCESSED`, `REVIEW_REQUIRED`, `RETRY_WAIT`), delivery attempts, timestamps, and a "Replay" action button.
- **Next.js Gateway Proxy** ([`route.ts`](file:///d:/01_MQ/0%20Projects/Fin_Projects/ledger-platform/frontend/app/api/ledger/[...path]/route.ts)):
  - Configured allowlists and header forwarding for `/api/v1/webhooks/razorpay`, `/reconciliation-runs`, `/results`, and `/replay`.

---

### 3. Verification & Acceptance Evidence

#### 3.1 Backend Test Suite
Executed using `.\mvnw.cmd test`:
- **Result**: `BUILD SUCCESS`
- **Total Tests**: **109 passed**, 0 failures, 0 errors, 0 skipped.
- **Key Suites**:
  - `WebhookIntakeVerificationTest`: 16/16 passed (signature validation, malformed payloads, dedup, forward-only state safety).
  - `ProviderPaymentReconciliationTest`: 7/7 passed (matching classifications, conflict detection, paging).
  - `ProviderPaymentFailureHandlingTest`: 11/11 passed (re-verification, idempotency, failure callbacks).
  - `LedgerPlatformApplicationTests`: 59/59 passed (schema Flyway V1–V11, operator role access, balance integrity).

#### 3.2 Frontend Test Suite and Static Checks
- **Typecheck**: `npm --prefix frontend run typecheck` — **PASS** (`tsc --noEmit`, 0 errors).
- **Lint**: `npm --prefix frontend run lint` — **PASS** (`eslint .`, 0 errors, 0 warnings).
- **Unit Tests**: `npm --prefix frontend test` — **PASS** (**36 / 36 tests passed** across 9 test files).
- **Production Build**: `npm --prefix frontend run build` — **PASS** (`next build` succeeded for all pages and dynamic routes).

#### 3.3 Live Review Container Integration Tests ([`scripts/verify-phase63-acceptance.py`](file:///d:/01_MQ/0%20Projects/Fin_Projects/ledger-platform/scripts/verify-phase63-acceptance.py))
Executed against `http://127.0.0.1:3000` on the active Compose review stack:
1. **Missing Webhook Signature**: Rejected with HTTP 400 (`INVALID_WEBHOOK_PAYLOAD`).
2. **Invalid Webhook Signature**: Rejected with HTTP 400 (`INVALID_WEBHOOK_SIGNATURE`).
3. **Valid `payment.captured` Intake**: Accepted with HTTP 200 `{"status":"OK"}`; persisted and marked `PROCESSED`.
4. **Idempotent Deduplication**: Re-delivering exact same webhook returned HTTP 200 without creating duplicate events or records.
5. **Out-of-Order Safety**: Late `payment.authorized` accepted with HTTP 200; state remained forward-safe (`CAPTURED_VERIFIED`).
6. **Fetch Attempt Webhooks**: `GET /api/v1/provider-payments/{attemptId}/webhook-events` returned 200 with persisted event history.
7. **Safe Webhook Replay**: Replayed existing event returning HTTP 200 with `ALREADY_PROCESSED` without violating trigger guards.
8. **Reconciliation Run**: `POST /api/v1/provider-payments/reconciliation-runs` returned HTTP 201 Created and persisted run.
9. **Reconciliation Results**: `GET /api/v1/provider-payments/reconciliation-runs/{runId}/results` returned classifications with complete audit details.
10. **UI Routes Verification**: All 5 UI routes returned HTTP 200 OK (`/transactions`, `/payments`, `/provider-payments`, `/reconciliation`, `/system-health`).

---

## 4. Safety Audit & Accounting Gate Verification

Executed read-only PostgreSQL query on `ledger-phase61-review-postgres`:
```sql
SELECT count(*) AS total_journal_transactions FROM public.journal_transactions;
SELECT count(*) AS total_journal_entries FROM public.journal_entries;
SELECT id, transaction_reference, description FROM public.journal_transactions;
```

**Result:**
- `total_journal_transactions`: **6**
- `total_journal_entries`: **12**
- All 6 transactions correspond exclusively to the initial demo wallet balances provisioned in Phase 5 (`demo-wallet-opening-v1:*` and `XFER-057d3224-*`).
- **Postings for Provider Payments**: **0 entries**.
- **Accounting Gate**: Gated under `ACCOUNTING_GATE_PENDING`.

---

## 5. Phase Close & Boundaries Out-of-Scope

Phase 6.3 is concluded with the following items deferred to future authorized phases:
1. **Ledger Posting**: Captured provider payments remain gated until accounting policies and debit/credit target accounts are approved.
2. **Public Webhook Exposure**: Webhook ingestion remains restricted to loopback/review environments.
3. **Manual Conflict Resolution**: Conflicting reconciliation outcomes are read-only audit records; manual resolution UI is Phase 7+.
4. **Refunds and Payouts**: Out of scope per project charter.
