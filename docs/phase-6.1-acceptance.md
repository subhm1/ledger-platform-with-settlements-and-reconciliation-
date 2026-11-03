# Phase 6.1 acceptance record

**Status: ACCEPTED for local single-operator review only.** This is not production approval.

## Scope and implemented behavior

Phase 6.1 includes the existing ledger posting and double-entry controls, transaction and settlement read APIs, CSV settlement import with duplicate detection, persisted reconciliation runs/results, and the Transactions, Reconciliation, and System Health pages. Reconciliation only classifies external evidence; it does not alter posted ledger transactions. Amounts remain integer minor units and settlement rows include currency precision metadata for exact display. No manual exception resolution, conflict replacement, multi-user ownership, or Razorpay support is included.

Financial API routes are authenticated by Spring Security. A configured local operator receives the `OPERATOR` role; protected API and aggregate health routes require it, unlisted routes are denied, and only liveness/readiness health probes are public inside the Compose network. The browser signs in through the Next.js server. The server issues an eight-hour HMAC-signed `HttpOnly`, `SameSite=Strict` cookie and keeps backend Basic credentials server-side. The proxy allowlists paths, validates mutations against the configured local port and loopback origin aliases, and forwards those credentials. Operator/session secrets are in ignored `.env.local-review`; do not publish or paste that file.

This is intentionally a single-operator local boundary. It does not authenticate distinct people, isolate owners/tenants, provide credential rotation or recovery, or establish a production identity provider. The review stack uses HTTP on loopback (`Secure` cookie is disabled for local HTTP); Docker administrators and processes with local host access remain trusted. Do not expose this stack beyond the workstation.

## Local Compose topology and data safety

`compose.yaml` and `compose.local-review.yaml` define PostgreSQL, backend, and frontend. The frontend publishes only `127.0.0.1:3000`; PostgreSQL has no host-published port; the backend has no host-published port and is reachable on the Compose network. The review database volume is `ledger-phase61-review_postgres_data`. The current database reports V1–V4 and `ledger_runtime` is not a superuser. Existing volume contents are retained across restarts. The authorized smoke checks used a nonexistent batch ID and made no database changes. V1–V4 were not edited.

The separate `ledger-platform_postgres_data` volume was not targeted by this work. Backend integration tests used disposable Testcontainers PostgreSQL. No reset, drop, truncate, or volume deletion was run.

## Verification evidence — 2026-10-25

Commands ran against `D:\01_MQ\0 Projects\Fin_Projects\ledger-platform`, except frontend commands ran in its `frontend` directory.

### Backend automated tests

- `.\mvnw.cmd --batch-mode --no-transfer-progress verify` — **PASS**, `BUILD SUCCESS`; 65 tests, 0 failures, 0 errors, 0 skipped. This includes 58 application integration tests and 7 CSV parser tests. Testcontainers used disposable PostgreSQL. `financialApiRequiresOperatorAuthenticationAndRole` covers anonymous read/write denial, wrong-role denial, and operator access. Existing tests cover posting balance/atomicity, idempotency/concurrency, append-only and runtime-role restrictions, CSV validation/duplicate import, reconciliation persistence, and no ledger mutation.

### Frontend tests and static checks

From `frontend`:

- `npm test` — **PASS**, 6 files and 15 tests, including proxy authentication, loopback-origin forwarding, invalid-origin rejection, and sign-in gating.
- `npm run lint` — **PASS**, no lint errors.
- `npm run typecheck` — **PASS**.
- `npm run build` — **PASS**, production build generated the three UI pages and dynamic auth/proxy handlers.

Vite prints an existing config-loader compatibility warning during tests; checks pass.

### Compose and live stack

- `docker compose --project-name ledger-phase61-review --env-file .env.local-review -f compose.yaml -f compose.local-review.yaml config --quiet` — **PASS**.
- `.\scripts\start-local-review.ps1` — **PASS**, sequentially rebuilt images and restarted existing isolated review containers. The script preserved existing env entries and appended only missing auth settings; it did not delete the retained volume.
- `.\scripts\verify-local-review.ps1` — **PASS**: all containers healthy; three UI routes HTTP 200; anonymous financial read and write HTTP 401; login and operator session successful; authorized same-origin request reached the backend and returned expected HTTP 404 for the absent batch; invalid-origin mutation HTTP 403; readiness UP; transaction and settlement reads returned expected pagination; unsupported proxy path HTTP 404; frontend loopback-only; PostgreSQL unpublished; schema V1–V4; runtime role not superuser.

These live checks establish routing and controls. They are not manual cross-browser visual QA or production penetration testing.

### Repository checks

- `git diff --check` — **PASS**, exit code 0. Git reported only existing LF-to-CRLF conversion notices.
- Existing unrelated modifications, untracked files, logs, backups, and migrations were preserved. No stage, commit, push, deployment, or Phase 6.2 work occurred.

## Repeatable verification commands

    .\mvnw.cmd --batch-mode --no-transfer-progress verify
    Push-Location frontend
    npm test
    npm run lint
    npm run typecheck
    npm run build
    Pop-Location
    docker compose --project-name ledger-phase61-review --env-file .env.local-review -f compose.yaml -f compose.local-review.yaml config --quiet
    .\scripts\start-local-review.ps1
    .\scripts\verify-local-review.ps1
    git diff --check

## Remaining limitations

No known Phase 6.1 local-review blocker remains based on the checks above. Owner/tenant isolation, production-grade identity and secret management, TLS, recovery, monitoring, and deployment hardening remain outside this acceptance and are required before production or multi-operator use. Phase 6.2 and Razorpay were not started.
