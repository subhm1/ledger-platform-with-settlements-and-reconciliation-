# Stage 2 — Internal wallet payments

## Scope

The Payments page contains only a simulated internal INR wallet transfer workflow between the five demo wallets provisioned in Stage 1. It has no provider collection, bank debit, or real-money flow.

## API

- `GET /api/v1/wallets` returns only the seeded five-wallet roster, current status, INR precision, and balances derived from posted journal entries.
- `POST /api/v1/wallets/transfers` accepts sender and recipient wallet IDs, a positive integer amount in paise, a stable transaction reference, an optional short reference, and an `Idempotency-Key` header.

Both endpoints require the existing operator authorization. The Next.js proxy permits only these exact wallet routes and retains same-origin checks and server-held backend credentials.

## Accounting and state rules

- A transfer is between two distinct active seeded INR wallets only.
- PostgreSQL validates the roster, serializes wallet access, enforces sufficient funds, and invokes the existing V3 double-entry posting function.
- The journal transaction debits the sender wallet liability and credits the recipient wallet liability for the same amount.
- The posting function and idempotency record commit atomically. Same-key/same-request retry returns the original transaction. Changed request data for the same key is rejected.
- Posted entries stay immutable. The receipt’s movement rows are two sides of the same atomic transaction, not separately processed bank steps.
- The SBI logo is illustrative only. The visible receipt states that no SBI account is connected and no SBI transaction occurred. All balances are simulated.

## Database change

V7 is forward-only and leaves V1–V6 untouched. It adds a restricted demo-wallet listing function and a transfer wrapper that accepts only the five seeded INR wallet identities. The runtime role receives only `EXECUTE` on those functions; it receives no direct wallet table access. The generic V3 transfer function and its permissions are unchanged.

Flyway V7 is applied to the persistent local review database. The earlier V5/V6 payment-attempt schema and any existing payment-attempt/webhook records are retained unchanged for historical integrity. Razorpay controllers, services, checkout UI, API proxy routes, and credentials are removed from the running application; no migration drops or rewrites those tables.

## Receipt and brand treatment

The receipt shows the posted amount, time, sender, recipient, reference, transaction ID, and matching debit/credit entries. It does not show a history action or claim a real bank debit. The local SBI SVG came from Wikimedia Commons, which identifies it as a public-domain text/logo file while noting trademark restrictions; the UI therefore labels the logo as illustrative and states that the service is not connected to SBI. A public portfolio deployment should retain this disclosure and confirm its intended brand use.
