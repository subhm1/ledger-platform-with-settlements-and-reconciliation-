# Phase 6.2 Part 3 — Payments UI acceptance

## Audit and implementation

The existing frontend uses Next.js 16.4.3, React 19.3.1, TypeScript 5.9.3, Vitest 5.0.5, and ESLint 9.39. It uses the App Router, an authenticated application shell, a same-origin Next.js API proxy, and existing Transactions, Reconciliation, and System Health pages. Before Part 3, there was no Payments route or navigation item, and the proxy did not allow payment-attempt requests. The backend already implemented operator-protected payment creation, attempt retrieval, Checkout signature verification, recovery for ORDER_UNKNOWN, and the Razorpay webhook inbox; it had no paginated attempt-list endpoint. Settlement and reconciliation are independent workflows without a reliable payment-attempt association. Captured collections remain non-posting and ACCOUNTING_GATE_PENDING.

The Payments page is /payments. It displays real backend attempts with server states, amount/currency, provider order ID, timestamps, and accounting gate. It supports paginated refresh and INR attempt creation using integer paise. It prevents accidental duplicate submissions and reuses an idempotency key for an ambiguous retry. Checkout is offered only with backend-provided Test Mode order data. Unknown orders expose investigation guidance and the existing recovery endpoint; they are never blindly recreated. A settlement/reconciliation link explains that capture does not establish settlement and no direct relationship is modeled.

A minimal backend GET /api/v1/payment-attempts?page=0&size=25 was added because the UI otherwise could not list existing attempts. It uses the existing paged API contract and operator security. The Next.js proxy allows only the exact payment collection, list/detail, verification, and recovery routes; mutations continue to require the existing signed operator session, same-origin loopback checks, and server-held Basic credentials. It forwards the create idempotency key.
The list API returns items, page, size, totalElements, and totalPages. Creation is POST /api/v1/payment-attempts with an Idempotency-Key header and amountMinor, currencyCode, purpose, and businessReference. Retrieval is GET /api/v1/payment-attempts/{attemptId}; recovery is POST /{attemptId}/recover-order; verification is POST /{attemptId}/verify-checkout with paymentId, orderId, and signature. All these APIs require the operator role.

## Checkout and trust boundary

On explicit operator action, the UI lazily loads Razorpay Checkout and supplies only the server-returned rzp_test_ public key ID, provider order ID, amount in minor units, and currency. The Checkout callback is sent to the existing backend verification endpoint. Browser callback data is never displayed as proof of success: the UI polls the backend status a bounded number of times and reports capture only when confirmed by the backend. Checkout dismissal and verification errors remain unresolved/error states. API and webhook secrets remain server-side.

Captured payments remain ACCOUNTING_GATE_PENDING; there is no ledger posting, refund, payout, or wallet-funding behavior. No payment-to-settlement link is fabricated. INR is the UI currency because that is the current Part 2 collection contract used in tests; the backend must have INR enabled for creation to succeed. In the local review stack, run `scripts/provision-local-review-inr.ps1`; it provisions only `INR` (exponent 2, enabled) in the explicitly labeled review database, is repeatable, and refuses to overwrite unexpected existing attributes. See the README for safeguards and the exact operator command.

## Configuration and safe local use

Use the existing server-side variables LEDGER_RAZORPAY_KEY_ID, LEDGER_RAZORPAY_API_SECRET, and LEDGER_RAZORPAY_WEBHOOK_SECRET. Only a rzp_test_ key is accepted for Checkout. Do not put secrets in frontend variables. Operator sign-in and backend proxy configuration continue to use LEDGER_OPERATOR_USERNAME, LEDGER_OPERATOR_PASSWORD, and existing backend configuration; see the repository README and .env.example for names only.

Run the app through the documented isolated local review setup and sign in as the configured operator. The payments UI is at http://localhost:3000/payments. Enable INR in the review database through the approved database provisioning procedure before attempting creation. Do not apply migrations to a persistent review database as part of this acceptance. For safe sandbox verification, configure Test Mode credentials and a reachable Test Mode webhook endpoint; create an attempt, complete Checkout with Razorpay test instruments, and confirm backend verification plus webhook inbox state. No real provider payment or webhook delivery was performed for this acceptance.

## Verification evidence

- Backend: .\mvnw.cmd verify passed 90 tests, 0 failures, 0 errors, 0 skipped. Testcontainers used disposable PostgreSQL; Flyway V1–V6 ran only in those containers.
- Frontend: npm test -- --reporter=verbose --maxWorkers=1 passed 30 tests in 8 files.
- npm ci --ignore-scripts --dry-run completed successfully. The local runtime was Node v24.13.0 / npm 11.6.2; npm emitted engine warnings for transitive jsdom/CSS packages requiring Node 24.15.0 or later. Use the documented Node 24.15+ version for supported development.
- npm run lint passed.
- npm run typecheck -- --incremental false passed without writing the existing TypeScript build-info cache.
- Production build: npm run build passed on Next.js 16.4.3; route output included /payments, /reconciliation, /system-health, /transactions, and the API handlers. The existing .next directory was restored and SHA-256 verified.
- No browser E2E test framework is configured; component and proxy tests use mocks.
- Razorpay Test Mode credentials, external payment completion, and dashboard webhook delivery were not verified.

## Remaining boundaries

Accounting approval is outstanding. Captured funds are not posted until the accounting owner approves the exact debit/credit accounts and policy in a future authorized phase. External sandbox connectivity and webhook delivery must be verified in a reachable Test Mode environment. The existing review database was not opened, migrated, or modified.



