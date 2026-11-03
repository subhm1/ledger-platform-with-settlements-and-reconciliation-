# Phase 6.3 — Webhook Delivery and Reconciliation Integration: Scope and Contracts

## 1. Phase context

Phase 6.2 delivered:
- Durable Razorpay Test Mode payment attempts (`payment_attempts` + `payment_attempt_events`).
- Checkout-flow verification with server-side signature validation.
- Safe failure-callback handling: unsigned failure callbacks transition to `PAYMENT_PENDING`; idempotent re-delivery is a no-op.
- A webhook inbox table (`payment_webhook_events`) with lifecycle-guarded state machine.
- An in-memory dedup guard and a best-effort event log entry in `payment_attempt_events`.

Phase 6.3 replaces the best-effort webhook handling with durable, idempotent, and auditable ingestion, and makes provider evidence available to the reconciliation layer.

---

## 2. Supported Razorpay webhook event types and state mappings

Only Test Mode events are processed. The following event types are **supported**; all others are recorded as `REVIEW_REQUIRED` and ignored without changing payment state.

| Razorpay event type | Mapped internal event | Payment state effect |
|---|---|---|
| `payment.authorized` | `WEBHOOK_PAYMENT_AUTHORIZED` | `READY_FOR_CHECKOUT` / `PAYMENT_PENDING` → `AUTHORIZED` |
| `payment.captured` | `WEBHOOK_PAYMENT_CAPTURED` | `AUTHORIZED` / `PAYMENT_PENDING` / `READY_FOR_CHECKOUT` → `CAPTURED_VERIFIED` |
| `payment.failed` | `WEBHOOK_PAYMENT_FAILED` | `READY_FOR_CHECKOUT` / `PAYMENT_PENDING` → `PAYMENT_PENDING` (unresolved; do not mark FAILED from webhook alone) |
| `order.paid` | `WEBHOOK_ORDER_PAID` | Observed; cross-check with payment state; no direct transition |

### State transition rules (webhook path)

- **Forward-only**: a webhook event can never regress an attempt that has already reached a later terminal state (`CAPTURED_VERIFIED`, `FAILED`).
- **Captured payments**: a `payment.captured` event on a `CAPTURED_VERIFIED` attempt is recorded as a duplicate observation; the attempt state is unchanged (idempotent no-op).
- **`payment.failed` from webhook**: Razorpay may deliver multiple failed events for partial payment attempts under a single order. The backend does **not** mark the attempt `FAILED` from webhook evidence alone; it records `PAYMENT_PENDING` (unresolved) and awaits server-side verification or the `order.paid` event.
- **Unsupported event types**: recorded in `payment_webhook_events` as `REVIEW_REQUIRED`; no payment state change.

---

## 3. Event persistence

Every accepted webhook event (valid signature, supported or unsupported) is persisted to `payment_webhook_events` **before** any state transition is attempted:

```
INSERT INTO payment_webhook_events
  (provider, environment, provider_event_id, event_type,
   provider_order_id, provider_payment_id, signature_verified,
   processing_state, attempt_count)
VALUES ('RAZORPAY', 'TEST', <event_id>, <event_type>,
        <order_id_from_payload>, <payment_id_from_payload>, TRUE, 'RECEIVED', 0)
ON CONFLICT (provider, environment, provider_event_id) DO NOTHING
```

The `ON CONFLICT DO NOTHING` ensures duplicate deliveries are harmless at the persistence layer. The row's `UNIQUE (provider, environment, provider_event_id)` constraint is the authoritative dedup anchor.

After persistence, the processing worker:
1. Sets `processing_state = 'PROCESSING'`, increments `attempt_count`.
2. Applies the payment state transition (idempotent as per Phase 6.2 rules).
3. On success: sets `processing_state = 'PROCESSED'`, records `processed_at`.
4. On transient failure: sets `processing_state = 'RETRY_WAIT'`, sets `next_attempt_at`.
5. On persistent/unrecoverable failure: sets `processing_state = 'REVIEW_REQUIRED'`, records `safe_error_code`.

---

## 4. Deduplication contract

- **Primary key**: `(provider, environment, provider_event_id)` unique constraint.
- **Duplicate delivery**: the second `INSERT` is silently ignored (`DO NOTHING`). The existing row's `processing_state` is not mutated.
- **In-memory dedup guard** (from Phase 6.2): retained for the single-instance review stack but no longer the authoritative dedup mechanism; the database constraint is authoritative.
- **Re-processing safety**: if a row exists in `RECEIVED` or `RETRY_WAIT`, a worker may pick it up and process it. If it exists in `PROCESSED`, no further processing occurs.

---

## 5. Retry and failure behavior

| Condition | `processing_state` | `next_attempt_at` | `attempt_count` limit |
|---|---|---|---|
| Transient gateway/DB error | `RETRY_WAIT` | +30s / +60s / +300s (exponential) | 5 attempts |
| Payment ID not found in Razorpay | `REVIEW_REQUIRED` | – | – |
| Attempt not found in our DB by receipt | `REVIEW_REQUIRED` | – | – |
| `attempt_count` exceeds limit | `REVIEW_REQUIRED` | – | – |
| Unsupported event type | `REVIEW_REQUIRED` | – | – |
| Processing success | `PROCESSED` | – | – |

Retry and background processing are **deferred to a later sub-phase**. For 6.3.2 the state is persisted and transitioned synchronously within the request. The retry table columns are present and ready.

---

## 6. Reconciliation integration

### Provider evidence in reconciliation

A provider payment reconciliation run matches:
- **Internal record**: a `payment_attempts` row in `CAPTURED_VERIFIED` state, identified by `provider_payment_id`.
- **Provider record**: a `payment_webhook_events` row with `event_type = 'payment.captured'` and `processing_state = 'PROCESSED'`, matched on `provider_payment_id`.

Match outcome classification:

| Outcome | Condition |
|---|---|
| `MATCHED` | Internal `CAPTURED_VERIFIED` + webhook `PROCESSED` with same `provider_payment_id` and matching amount |
| `UNMATCHED_INTERNAL` | `CAPTURED_VERIFIED` attempt with no corresponding `PROCESSED` webhook event |
| `UNMATCHED_PROVIDER` | `PROCESSED` capture event with no corresponding `CAPTURED_VERIFIED` attempt |
| `DUPLICATE` | Multiple `PROCESSED` capture events for the same `provider_payment_id` |
| `CONFLICTING` | `MATCHED` but amount or currency differs between internal record and provider evidence |

Provider payment reconciliation runs are separate from the existing settlement reconciliation runs (`reconcile_settlement_batch`). Their results are persisted in a dedicated table (`provider_payment_reconciliation_runs`) added in migration V11.

---

## 7. Out-of-scope items (explicit)

The following are **explicitly excluded** from Phase 6.3:

| Item | Why deferred |
|---|---|
| Live (non-Test-Mode) payments | Phase gate: only Test Mode until accounting approval |
| Ledger journal posting | Accounting gate is pending; no posting path is opened |
| Public deployment / internet-reachable webhook endpoint | Requires infrastructure approval |
| Manual conflict resolution UI | Phase 7+ |
| Refunds and payouts | Phase 7+ |
| Background retry scheduler | Phase 7 (retry columns are ready; background job is deferred) |
| Webhook event replay across instances | Single-instance review stack; cross-instance replay is Phase 7 |
| Real settlement–payment linking | No reliable association model; out of scope per Phase 6.2 docs |

---

## 8. API surface added in Phase 6.3

| Method | Path | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/webhooks/razorpay` | None (signature auth) | Ingest and durably persist a Razorpay webhook event |
| `GET` | `/api/v1/provider-payments/{attemptId}/webhook-events` | Operator | List persisted webhook events for an attempt |

The existing webhook controller at `/api/v1/webhooks/razorpay` is retained; only the backing implementation is upgraded.

---

## 9. Safety gates re-confirmed

- No webhook or reconciliation code path calls `INSERT INTO journal_transactions` or `INSERT INTO journal_entries`.
- No webhook code path calls `INSERT INTO journal_entries`.
- All captured provider payments remain `ACCOUNTING_GATE_PENDING`.
- Webhook secret is never logged, returned in responses, or written to committed files.
- Applied Flyway migrations V1–V9 are not edited.
