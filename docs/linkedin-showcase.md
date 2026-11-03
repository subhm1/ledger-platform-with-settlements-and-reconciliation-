# LinkedIn Showcase Post Draft

Below is the ready-to-publish technical showcase post for the **Ledger Platform** project.

---

## Post Copy (Ready to Publish)

Building financial software that handles money demands a fundamentally different engineering mindset. In payments, "eventual consistency" without strict invariants quickly becomes balance corruption, untraceable float, and phantom money.

Over the past few weeks, I built **Ledger Platform** — a double-entry financial core, multi-asset wallet engine, and dual reconciliation platform engineered from first principles with **Java 21**, **Spring Boot 4**, **PostgreSQL 18**, and **Next.js 16**.

Here are the key architectural decisions that keep the system correct under pressure:

### 1. Database-Level Immutability Triggers
Application validation alone is not enough. In Ledger Platform, PostgreSQL triggers (`reject_financial_mutation`) enforce that posted journal lines can **never** be updated or deleted at the SQL layer. Every transaction strictly balances debits and credits to the exact minor unit ($0.00$ discrepancy). Errors can only be resolved through explicit, audit-linked compensating reversals.

### 2. Multi-Asset Wallets with Negative-Balance Invariants
Wallets enforce optimistic/pessimistic balance guards preventing overdrafts during concurrent internal transfers. Transfers execute atomically with dedicated business references and idempotency keys.

### 3. Durable, Idempotent Webhook Intake
External webhooks from payment gateways arrive out of order, retry unpredictably, or fail silently. For our Razorpay Test Mode integration:
- Signatures are verified against the raw request body using HMAC-SHA256 (supporting both hex and Base64 digests).
- An authoritative unique database constraint `(provider, environment, provider_event_id)` makes duplicate delivery an idempotent no-op.
- A forward-only state engine ensures delayed authorization or failure callbacks can never regress an already-captured payment.

### 4. Dual Reconciliation Engine
The system bridges internal postings with external ground truth across two distinct workflows:
- **Settlement File Ingestion**: Ingests multi-thousand row CSV settlement batches with row-level validation and runs deterministic matching against posted ledger entries.
- **Provider Payment Reconciliation**: Correlates internal captures with external webhook capture evidence, explicitly classifying outcomes (`MATCHED`, `UNMATCHED_INTERNAL`, `UNMATCHED_PROVIDER`, `DUPLICATE`, `CONFLICTING`).
- All reconciliation runs are immutable audit records protected by PostgreSQL triggers.

### 5. The Accounting Gate
One crucial boundary: captured provider payments remain strictly gated under `ACCOUNTING_GATE_PENDING`. Webhooks record verified provider evidence, but no automated money creation occurs on the financial ledger until accounting rules and target accounts are formally approved.

---

### Verification & Testing Metrics:
- **109 / 109 Backend Tests Passed** via Spring Boot & Testcontainers PostgreSQL.
- **36 / 36 Frontend Unit Tests Passed** across 9 test suites (Vitest).
- **10 / 10 Live Stack Integration Checks Passed** verifying raw signature rejection, idempotent dedup, out-of-order safety, and 0 ledger leaks.
- Zero financial postings created for provider payments across all test suites.

GitHub Repository: https://github.com/subhm1/ledger-platform-with-settlements-and-reconciliation-
Release Tag: `v1.0.0`

*(Note: Operates strictly with Razorpay Test Mode credentials in an isolated local review stack; no real financial accounts or live payment routing).*

#SoftwareEngineering #Fintech #SystemDesign #Java #SpringBoot #PostgreSQL #NextJS #DistributedSystems #DoubleEntry #CleanArchitecture
