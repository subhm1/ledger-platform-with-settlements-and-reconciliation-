# Phase 1 — Accounting Foundation

Status: COMPLETE

## Verified acceptance evidence

- Flyway V1 and V2 applied successfully to PostgreSQL.
- All six accounting tables exist: currencies, accounts, journal_transactions,
  journal_entries, idempotency_records, and reversal_audits.
- The pgcrypto extension is installed.
- The controlled posting function exists with SECURITY DEFINER enabled.
- The posting function is owned by ledger_executor.
- ledger_runtime cannot directly insert journal transactions or journal entries.
- ledger_runtime can execute the controlled posting function.
- Runtime and migration database credentials were tested successfully.
- Maven clean verify completed with BUILD SUCCESS.
- Integration tests: 8 passed, 0 failures, 0 errors, 0 skipped.
- The Git working tree was clean at the last verification.

## Deferred to the correctness-under-pressure phase

- Concurrent posting and idempotency race conditions.
- Explicit rollback and failure-atomicity edge cases.
- Idempotency-key reuse with a different request fingerprint.
- Currency mismatch and reversal-of-reversal cases.
- Audit immutability and additional database security tests.

These deferred cases are not represented as tested or verified in Phase 1.

## Scope boundary

Phase 1 establishes the accounting schema, controlled posting foundation,
database access boundaries, migration setup, and baseline integration coverage.
It does not certify the entire platform as production-ready.
