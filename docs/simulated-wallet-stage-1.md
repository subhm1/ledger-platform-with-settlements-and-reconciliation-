# Stage 1 — Simulated wallet foundation

Stage 1 uses the existing V3 wallet and journal model. It does not create a real bank connection, use Razorpay, or apply a schema migration. Existing V1–V6 migrations remain unchanged, and runtime database privileges are not broadened.

The five local demo identities are `demo:shubham`, `demo:ashwin`, `demo:arnav`, `demo:shreya`, and `demo:swati`. Each gets an INR customer-wallet liability account. The local-only setup command accepts the initial balance in integer paise and posts one balanced opening event per wallet: debit the simulated `DEMO:TREASURY:INR` asset and credit the wallet liability. These records are explicitly simulated; they do not represent SBI or other bank funds.

INR must already be present and enabled with exponent 2. Provision it first, if necessary, using `scripts/provision-local-review-inr.ps1`. Then run the wallet setup from PowerShell with the intended amount per wallet, for example:

```powershell
.\scripts\provision-local-review-wallets.ps1 -InitialBalancePaise 10000
```

That example means INR 100.00 per demo wallet. The command checks the fixed Compose project and PostgreSQL service labels, the database and owner role, and expected account/wallet attributes. It runs the five wallet creations and balanced postings in one transaction. It does not overwrite unexpected rows. Fixed idempotency keys make repeat execution safe with the same amount; attempting a different amount for an already provisioned wallet is rejected rather than silently adjusting its balance. Corrections require a separate, audited adjustment workflow.

This is an operator setup for the isolated `ledger-phase61-review` database only. Do not run it against the main `ledger-platform` Compose project or shared/production data. This stage does not expose wallet APIs or user-facing transfer flows; those belong to the subsequent Payments implementation stage.
