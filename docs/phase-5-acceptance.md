# Phase 5 — Reliability, Stress Testing, and Operational Hardening

## Acceptance status

Implemented and verified against disposable PostgreSQL 18 Testcontainers. No persistent database was used.

## Reliability checks

- Concurrent API requests with the same scope, idempotency key, and body return the same transaction ID. They persist one transaction, one idempotency record, and a balanced two-entry posting.
- Concurrent requests that reuse one key with different amounts produce one successful response and one `409` conflict. Persisted accounting remains balanced.
- A client retry after an unknown response outcome returns the already committed transaction ID when it repeats the identical request.
- A 32-posting workload released across eight workers leaves 32 unique postings and idempotency records. The test checks persisted debit and credit totals, entry counts, and entry currencies independently of the posting return values.
- Two simultaneous reversals of one original transaction produce exactly one successful reversal and one rejection.
- Existing settlement and reconciliation concurrency checks cover identical imports, conflicting imports, and concurrent reconciliation invocations. Every reconciliation invocation creates a distinct run. Concurrent calls are serialized per batch and receive successive run numbers. Reruns preserve prior run and result history.
- A late PostgreSQL posting failure must roll back the complete transaction. See the direct wallet-overdraft assertion in the integration suite.

## Health and operational checks

`GET /actuator/health` returns `200` with status `UP` when the application is connected to its disposable PostgreSQL database. Component detail is not exposed by the configured endpoint, so the acceptance check intentionally uses the public aggregate status.

For an unsuccessful settlement import, inspect the HTTP error code and message. The current API does not expose a batch lookup endpoint or an operator-facing validation report for failed imports. Authorized database access is needed to inspect persisted batches and row validation fields. A successful import response includes a batch identifier. The database import function accepts an existing source/report identity only when its content hash matches, then returns the existing batch identifier. A different hash for that identity is rejected. The current API has no correction or replacement workflow for that conflict; do not edit imported rows directly.

Each reconciliation invocation creates a new run with a new run number and result rows. Prior runs remain available as history. This is rerun history, not request-level idempotency. The current implementation has no controlled resolution API or database function, so mismatches cannot be resolved through an application workflow and no resolution audit records can be created through a supported operation. The audit table exists, but direct writes are intentionally denied to runtime and executor roles.

Reconciliation matches by exact transaction reference and currency. It compares the report's `gross_amount_minor` with the internal transaction's total debit amount. Fees and signed adjustments are validated and retained as report evidence, but do not change the amount used for matching. Under this contract, a report reference must identify the original gross ledger transaction. `INTERNAL_ONLY` detection considers only currencies represented by valid rows in the report, so transactions in omitted currencies are not classified as internal-only. `AMBIGUOUS` is practically unreachable while the global uniqueness constraint on `transaction_reference` remains in place. Reconciliation does not mutate ledger postings, journal entries, or wallet balances; the integration test compares ledger snapshots before and after reruns.

If reconciliation must be rerun, record that it creates another historical run. If the database is unavailable, use the deployment's established database recovery procedure; backup restoration and recovery decisions are outside this application's implemented operational workflows. For an unknown posting outcome, retry with the identical scope, idempotency key, and request body. The settlement API requires the same source/report identity and content hash to reuse an import.

The suite verifies database constraint rollback and retry after an unknown posting HTTP outcome. It does not simulate a client retry of the multipart settlement endpoint, process termination, network partitions, forced deadlocks, storage faults, or backup restoration. Those require separate environment-level exercises. Same-content settlement replay is verified at the database function level.

## Query-plan baseline

The disposable performance check imports 3,000 synthetic valid settlement rows, runs `ANALYZE`, then executes the exact `(batch_id, transaction_reference)` lookup with `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`. Acceptance requires the `settlement_records_reference_idx` index and exactly one returned row. The previously recorded run took 0.029 ms with 3 shared buffer hits. A fresh run on 2026-10-25 took 0.037 ms with 3 shared buffer hits and used the same index. These are local plan regression measurements, not production latency or capacity guarantees. Posting, idempotency, and reconciliation throughput have workload correctness checks but no production-scale latency target.

## Verification record

- Environment: Windows workstation, Java 21, Maven Wrapper, disposable PostgreSQL 18.6 Testcontainers.
- Maven settings: `%TEMP%\ledger-maven-settings.xml`; project-local `.m2-local` repository.
- Docker workaround: `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.
- Final full command: `mvnw.cmd --settings %TEMP%\ledger-maven-settings.xml --batch-mode --no-transfer-progress clean verify`, with `MAVEN_OPTS=-Xms128m -Xmx768m`, `JAVA_TOOL_OPTIONS=-XX:CICompilerCount=2 -XX:ActiveProcessorCount=4 -Xss512k`, and `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine`.
- Previously recorded result: **BUILD SUCCESS**, 60 tests passed, 0 failures, 0 errors, 0 skipped; total Maven time 25.104 seconds (2026-10-25).
- Fresh verification on 2026-10-25 00:28:33 +05:30: **BUILD SUCCESS**, 60 tests passed (53 integration tests and 7 parser tests), 0 failures, 0 errors, 0 skipped; Maven time 23.210 seconds. Docker/Testcontainers connected to disposable PostgreSQL 18.6 using the configured Docker Desktop named pipe. No persistent database was used.
