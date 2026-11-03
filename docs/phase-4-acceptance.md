# Phase 4 — Settlement Import and Reconciliation

Status: PHASE 4 ACCEPTANCE COMPLETE — repository-level checks passed against disposable PostgreSQL 18. This is not production deployment approval.

## Acceptance checklist

- [x] CSV parser validates exact header order, strict UTF-8, CSV quoting, minor-unit arithmetic, and parsed invalid-row preservation. Evidence: 7 parser tests passed, including reordered-header rejection.
- [x] Import API/database function, idempotency, changed-content conflict, and invalid-row preservation. Evidence: 46 PostgreSQL integration tests passed; same-hash race and conflicting-hash race tests verify final persisted row counts.
- [x] Reconciliation outcomes, exact reference/currency, gross matching, half-open period, rerun history, and rule version. Evidence: PostgreSQL integration tests passed.
- [x] Ledger immutability. Evidence: reconciliation test compares persisted transaction fields, journal entries, and account balances before and after reruns.
- [x] Runtime and executor privilege boundaries. Evidence: PostgreSQL tests assert `ledger_executor` has INSERT on the four tables used by its functions and no INSERT on `reconciliation_resolution_audits`; a real-result audit INSERT under `ledger_runtime` is rejected, as are direct runtime batch INSERT, UPDATE, and DELETE attempts. Import/reconciliation calls succeed.
- [x] Migration and database constraints run on disposable PostgreSQL 18. V4 applied successfully in Testcontainers; no persistent database was used.
- [x] Concurrent reconciliation. Evidence: simultaneous calls create distinct sequential historical runs (run numbers 1 and 2).
- [x] Import failure atomicity. Evidence: an import with a valid row followed by a malformed row fails and leaves no batch or record rows.
- [x] HTTP validation exercised for invalid headers/order, malformed quoting, invalid UTF-8, oversized uploads, blank/overlong source, blank report ID, invalid/reversed period, and malformed timestamp; tested responses assert status and relevant stable codes.
- [x] Full build and tests: `clean verify` exit 0; 53 tests, 0 failures, 0 errors, 0 skipped.
- [x] `git diff --check` passed.
- [ ] Query plans/index performance not measured on representative production-scale data; no speculative indexes added. This remains operational follow-up, not a correctness blocker for this scoped phase.
- [ ] Production migration safety, deployment, and production security are not established by local repository tests.
- [x] V1–V3 source files are unchanged. Backup files were not modified.

## Contract and known limitations

- Matching is by exact transaction reference and currency. The reported gross amount is compared with the internal transaction's total debit amount. Fees and signed adjustments are validated and retained but do not change matching.
- `INTERNAL_ONLY` detection only considers currencies represented by valid rows in the report; omitted currencies cannot be detected.
- `AMBIGUOUS` is effectively unreachable while `transaction_reference` remains globally unique. The uniqueness rule is preserved and no artificial test is added.
- A resolution audit table exists, but no controlled resolution function or API is exposed. Resolution mutations remain unavailable.
- Repository tests do not establish production deployment safety or production readiness.

## Scope constraints

- Use integer minor units for all money.
- Reconciliation must not create, update, or delete ledger postings or wallet balances.
- Each reconciliation rerun must preserve prior results and record its rule version.
- Do not add a frontend, real provider integration, FX, Kafka, or unrelated refactors in this phase.
- Do not apply V4 to a persistent database or commit until acceptance checks pass.

## Verification record

- Testcontainers initially selected `npipe:////./pipe/docker_engine`, while Docker Desktop's active context endpoint was `npipe:////./pipe/dockerDesktopLinuxEngine`. Setting `DOCKER_HOST` to the active endpoint for the test process resolved discovery without changing project/global configuration or Windows permissions.
- The first PostgreSQL run exposed a missing temporary schema `CREATE` grant around the V4 function ownership transfer. V4 now follows V2/V3: grant for ownership transfer, then revoke.
- PostgreSQL execution then showed the security-definer owner needed narrowly scoped INSERT and status UPDATE grants because the Phase 4 tables are owned by `ledger_owner`. Those function-required grants remain; the resolution audit has no direct INSERT grant. The runtime login is separately tested and cannot mutate those tables.
- Review found an earlier `GRANT SELECT, INSERT` across all five Phase 4 tables, which accidentally left `ledger_executor` with direct resolution-audit INSERT. V4 now grants SELECT across all five and only grants INSERT on the four tables used by import/reconciliation. The new privilege assertion and actual restricted-runtime operation test pass.
- `.m2-local` and a temporary Maven settings file were used to avoid the inaccessible configured user-cache lock. JVM memory was reduced for the final run after one native-memory allocation failure.
- Final command: `C:\Users\Sub\Projects\ledger-platform\mvnw.cmd --settings C:\Users\Sub\AppData\Local\Temp\ledger-maven-settings.xml --batch-mode --no-transfer-progress clean verify` with `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`, `MAVEN_OPTS=-Xms128m -Xmx768m`, and `JAVA_TOOL_OPTIONS=-XX:CICompilerCount=2 -XX:ActiveProcessorCount=4 -Xss512k`. Result: exit 0, `BUILD SUCCESS`, 53 tests passed, none failed, errored, or skipped after the privilege correction.
