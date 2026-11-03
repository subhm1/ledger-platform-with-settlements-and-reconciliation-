# Ledger Platform

A production-oriented double-entry financial ledger, multi-asset wallet system, settlement engine, and payment reconciliation platform built with Java 21, Spring Boot, PostgreSQL 18, Flyway, and Next.js 16.

---

## Architecture Overview

```
                      +---------------------------------------+
                      |         Operations Web UI             |
                      |   (Next.js 16 / React 19 / Tailwind)  |
                      |          http://127.0.0.1:3000        |
                      +-------------------+-------------------+
                                          |
                              Same-Origin API Proxy
                            (Session Cookie + Loopback)
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                            Ledger Backend Service                                 |
|                         (Java 21 / Spring Boot 4)                                 |
|                                                                                   |
|  +--------------------+   +--------------------+   +---------------------------+  |
|  | Double-Entry Core  |   | Wallet Engine      |   | Settlement Reconciliation |  |
|  | - Append-only Txns |   | - Balance check    |   | - CSV import & validation |  |
|  | - Balanced lines   |   | - Atomic transfers |   | - Run history & matching  |  |
|  +--------------------+   +--------------------+   +---------------------------+  |
|                                                                                   |
|  +---------------------------------------------+   +---------------------------+  |
|  | Provider Payments (Razorpay Test Mode)      |   | Provider Reconciliation   |  |
|  | - Order creation & Test Checkout            |   | - Matches internal vs ext |  |
|  | - Durable HMAC-SHA256 Webhook Ingestion     |   | - Identifies discrepancies|  |
|  | - Forward-only state engine & dedup guard   |   | - Immutable run records   |  |
|  +---------------------------------------------+   +---------------------------+  |
|                                                                                   |
|                 [ACCOUNTING GATE: Captured Payments -> Pending Approval]           |
+-----------------------------------------+-----------------------------------------+
                                          |
                                 Non-superuser roles
                                          |
                                          v
+-----------------------------------------------------------------------------------+
|                               PostgreSQL 18 Database                              |
|  - Flyway Migrations (V1 to V11)                                                  |
|  - Immutable triggers: reject_financial_mutation(), reject_provider_mutation()     |
|  - Lifecycle guards: guard_payment_attempt(), guard_payment_webhook_lifecycle()   |
+-----------------------------------------------------------------------------------+
```

---

## Core Capabilities

1. **Immutable Double-Entry Ledger Core**
   - Strictly balanced debits and credits across all transactions.
   - Database-level immutability triggers (`reject_financial_mutation`) prevent update or deletion of posted journal lines.
   - Audit-logged reversals referencing the original transaction.

2. **Multi-Asset Wallets & Atomic Transfers**
   - Negative-balance prevention and concurrency protection.
   - Idempotent transfer execution with dedicated business reference tracking.
   - Pre-seeded simulated demo wallets for testing and local evaluation.

3. **External Settlement File Ingestion & Reconciliation**
   - Multipart CSV settlement batch import with row-level validation.
   - Deterministic matching against posted ledger entries (`MATCHED`, `EXTERNAL_ONLY`, `INTERNAL_ONLY`, `AMOUNT_MISMATCH`, etc.).
   - Persistent run and result history without mutating original financial entries.

4. **Provider Payments (Razorpay Test Mode)**
   - Server-side order creation and public Test Mode checkout options.
   - Server-side payment verification against provider APIs.
   - Transparent lifecycle stepper (`READY_FOR_CHECKOUT`, `PAYMENT_PENDING`, `CAPTURED_VERIFIED`, `FAILED`, `ORDER_UNKNOWN`, `REVIEW_REQUIRED`).
   - Order recovery workflows for ambiguous network outcomes.

5. **Durable, Idempotent Webhook Processing**
   - Dedicated endpoint: `POST /api/v1/webhooks/razorpay`.
   - Raw payload HMAC-SHA256 signature verification supporting hexadecimal and Base64 digests with constant-time equality checks.
   - Authoritative database deduplication via unique constraints (`provider, environment, provider_event_id`).
   - Forward-only state transition engine: late or duplicate out-of-order events cannot regress verified payment state.
   - Safe event replay endpoint (`POST /api/v1/provider-payments/webhook-events/{id}/replay`).

6. **Provider Payment Reconciliation Engine**
   - Automatically correlates internal payment attempts with external provider webhook captures.
   - Classifies outcomes: `MATCHED`, `UNMATCHED_INTERNAL`, `UNMATCHED_PROVIDER`, `DUPLICATE`, and `CONFLICTING`.
   - Durable, immutable run records protected by database triggers.

7. **Operations UI Console**
   - Built with Next.js 16 (App Router), React 19, TypeScript, and Tailwind CSS.
   - Five operational views:
     - `/transactions`: Real-time journal transaction explorer with debit/credit balance inspection.
     - `/payments`: Simulated internal wallet transfers between demo accounts.
     - `/provider-payments`: Customer collection flow, live lifecycle tracker, and webhook evidence panel.
     - `/reconciliation`: Tabbed console for Settlement batch files and Provider payment reconciliation runs.
     - `/system-health`: Container health and dependency status probes.

---

## Safety Boundaries & Accounting Gate

> [!IMPORTANT]
> **Provider Payment Accounting Gate**: Captured provider payments remain `ACCOUNTING_GATE_PENDING`. Neither webhook ingestion nor reconciliation runs post entries to the financial ledger (`journal_transactions` or `journal_entries`). Real balance posting is gated until accounting policies and target accounts are formally approved.
>
> **Test Mode Only**: The system operates exclusively with Razorpay Test Mode instruments (`TEST` environment, `rzp_test_*` keys). No real money, credit cards, bank accounts, refunds, or recipient payouts are processed.
>
> **Conflict Resolution**: Reconciliation discrepancies are stored as immutable audit records. Manual conflict replacement UI is deferred to Phase 8+.

---

## Technology Stack

- **Backend**: Java 21, Spring Boot 4, Spring JDBC, Spring Security, Spring Boot Actuator
- **Database**: PostgreSQL 18, Flyway (Migrations V1–V11)
- **Frontend**: Next.js 16 (App Router), React 19, TypeScript 5.9, Tailwind CSS 4, Radix UI, Lucide Icons
- **Testing**: JUnit 5, Mockito, Testcontainers (PostgreSQL), Vitest 5, ESLint 9
- **Deployment & Containers**: Docker, Docker Compose, Multi-stage Dockerfiles

---

## Prerequisites

- **Java Development Kit (JDK)**: Version 21 (Temurin or equivalent)
- **Node.js**: Version 24.15+ and npm 11+
- **Docker Desktop**: With Linux containers enabled
- **Git**

Maven does not need to be installed globally; use the included `mvnw` (or `.\mvnw.cmd` on Windows).

---

## Local Review Environment Setup

The repository includes a self-contained local review environment using Docker Compose:

### 1. Start the Review Stack

From PowerShell on Windows:

```powershell
.\scripts\start-local-review.ps1
```

Or using Docker Compose directly:

```bash
docker compose --project-name ledger-phase61-review --env-file .env.local-review -f compose.yaml -f compose.local-review.yaml up -d --build
```

This starts:
- `ledger-phase61-review-postgres`: PostgreSQL 18 on private container network (port 5432 unpublished).
- `ledger-phase61-review-backend-1`: Spring Boot application on private container network (port 8080 internal).
- `ledger-phase61-review-frontend-1`: Next.js web application published on `127.0.0.1:3000`.

### 2. Provision Currencies & Demo Wallets

Before creating payments or transfers, provision the approved currency and seeded wallets:

```powershell
# 1. Enable INR in the database
.\scripts\provision-local-review-inr.ps1

# 2. Seed five demo accounts with initial opening balances
.\scripts\provision-local-review-wallets.ps1
```

Both scripts are idempotent and safe to run multiple times.

### 3. Open the Console

Navigate to **http://localhost:3000** in your browser.
Log in using the operator credentials defined in `.env.local-review` (default: `ledger-operator`).

### 4. Stop the Review Stack

To stop containers while preserving the database volume and data:

```powershell
.\scripts\stop-local-review.ps1
```

*(Do not use `docker compose down -v` unless you explicitly want to discard all local review data).*

---

## Verification & Automated Tests

### Full Test Matrix Execution

Run the complete suite of verification commands:

```powershell
# 1. Backend automated tests (109 tests via Testcontainers)
.\mvnw.cmd test

# 2. Frontend typecheck
npm --prefix frontend run typecheck

# 3. Frontend linting
npm --prefix frontend run lint

# 4. Frontend unit tests (36 tests)
npm --prefix frontend test

# 5. Frontend production build
npm --prefix frontend run build

# 6. Live review stack integration verification
python scripts/verify-phase63-acceptance.py
```

### Automated Live Stack Checks

The Python verification script ([`scripts/verify-phase63-acceptance.py`](scripts/verify-phase63-acceptance.py)) performs end-to-end testing against the active review stack:
1. Operator sign-in & session establishment.
2. Webhook signature rejection tests (missing signature & invalid signature &rarr; 400).
3. Webhook delivery (`payment.captured` &rarr; 200, state transition).
4. Webhook deduplication (identical delivery &rarr; 200 idempotent).
5. Out-of-order handling (late `payment.authorized` &rarr; 200 without state regression).
6. Webhook event listing & safe replay.
7. Provider reconciliation run trigger (`POST /api/v1/provider-payments/reconciliation-runs`).
8. Reconciliation result classification audit.
9. Verification of all 5 UI routes (`/transactions`, `/payments`, `/provider-payments`, `/reconciliation`, `/system-health`).
10. SQL check confirming zero ledger postings for provider payments (`ACCOUNTING_GATE_PENDING`).

---

## Configuration Reference

Key environment variables (managed in `.env.local-review` for review and `.env` for standard compose):

| Variable | Description |
|---|---|
| `POSTGRES_PASSWORD` | PostgreSQL bootstrap superuser password |
| `LEDGER_DB_PASSWORD` | Application runtime database user password |
| `LEDGER_FLYWAY_PASSWORD` | Migration runner database user password |
| `LEDGER_OPERATOR_USERNAME` | Operations console operator username |
| `LEDGER_OPERATOR_PASSWORD` | Operations console operator password (&ge; 24 chars) |
| `LEDGER_SESSION_SECRET` | HMAC cookie session encryption key (&ge; 32 chars) |
| `LEDGER_RAZORPAY_KEY_ID` | Razorpay Test Mode Key ID (`rzp_test_*`) |
| `LEDGER_RAZORPAY_API_SECRET` | Razorpay Test Mode API Secret |
| `LEDGER_RAZORPAY_WEBHOOK_SECRET` | Razorpay Test Mode Webhook Secret |

*Secrets are never logged or exposed in API error envelopes or client bundles.*

---

## Database Migrations (Flyway)

Migrations are located in `src/main/resources/db/migration/`:

- `V1__initial_setup.sql`: Base ledger schema, currencies, accounts, journal transactions.
- `V2__create_accounting_schema.sql`: Double-entry validation triggers and reversal audit tables.
- `V3__create_wallets_and_transfer_controls.sql`: Wallets, balance checks, and transfer primitives.
- `V4__create_settlement_reconciliation.sql`: Settlement batch import, record storage, and reconciliation runs.
- `V5__create_payment_attempts_and_webhook_inbox.sql`: Payment attempts, webhook inbox, and lifecycle guards.
- `V6__allow_verified_unknown_order_recovery.sql`: Order recovery state transition permissions.
- `V7__expose_demo_wallets_and_transfers.sql`: Pre-seeded demo wallets and internal transfers.
- `V8__payment_attempt_event_history.sql`: Append-only payment attempt event history table.
- `V9__allow_review_recovery_transitions.sql`: Recovery transition updates for review workflows.
- `V10__webhook_durable_ingestion.sql`: Webhook payload audit storage and attempt event linkage.
- `V11__provider_payment_reconciliation.sql`: Provider reconciliation runs, results, and immutability trigger.

---

## License & Project Status

This repository is maintained as a portfolio engineering project demonstrating robust transactional integrity, idempotent event-driven architecture, and financial audit standards. Not authorized for production payment processing.
