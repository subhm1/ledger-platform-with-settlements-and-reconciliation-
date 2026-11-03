# Stage 3 — Transactions

## Scope

The Transactions workspace is the read-only ledger explorer for the simulated internal INR transfers built in Stage 2. A successful transfer receipt links directly to the persisted journal transaction. Opening `/transactions?transactionId=<id>` loads the same detail drawer, making a receipt link shareable and refresh-safe.

## Transaction review

- The list reads posted transactions from `GET /api/v1/transactions` and supports the existing reference and currency filters.
- The detail view reads `GET /api/v1/transactions/{transactionId}` and shows debit and credit totals using integer minor units, plus an explicit balance check.
- Demo wallet account IDs are labelled with the seeded wallet names while the raw account code and accounting role remain visible for audit.
- Wallet names are resolved through the restricted `public.list_demo_wallets()` function from V7. The runtime API does not receive direct wallet table access. Non-demo accounts retain their account code as the display name.
- Posted transactions remain immutable; this page has no edit, delete, or reversal action.

## Verification

The API account labels and exact transaction detail are asserted in `DemoWalletProvisioningTests.verifyWalletTransferApi`. Frontend behavior is covered by `TransactionExplorer` tests for a balanced journal, friendly wallet names, and receipt deep links; the wallet receipt test verifies its transaction link. No database migration or persistent database setup is required for Stage 3.

Run checks from the repository root and `frontend` directory:

```powershell
$env:MAVEN_OPTS='-Xmx512m -Duser.timezone=Asia/Kolkata'
.\mvnw.cmd --batch-mode --no-transfer-progress -DforkCount=0 -DargLine=-Xmx512m test
Push-Location frontend
npm test
npm run lint
npm run typecheck
npm run build
Pop-Location
```
