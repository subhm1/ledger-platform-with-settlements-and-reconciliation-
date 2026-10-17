# Ledger Platform

A production-oriented double-entry ledger, settlement, and reconciliation platform built with Java 21, Spring Boot, PostgreSQL, and Flyway.

## Current Status

Phase 1 — accounting schema and controlled posting are under review. Do not treat the database migration as production-approved until the deployment and security checks are complete.

## Technology Stack

- Java 21
- Spring Boot
- Spring JDBC
- PostgreSQL 18
- Flyway
- Spring Boot Actuator
- Maven Wrapper
- JUnit 5
- Testcontainers
- Docker Compose
- GitHub Actions

## Prerequisites

- Java Development Kit (JDK) 21
- Git
- Docker Desktop with the Linux container engine enabled

Maven does not need to be installed globally; use the included Maven Wrapper.

## Start PostgreSQL

From the project root:

    docker compose up -d

Check its status:

    docker compose ps

The local Compose configuration defines the development database and credentials. Inspect `compose.yaml` before connecting. Never reuse development credentials in production.

## Run the Application

Start the application:

    .\mvnw.cmd spring-boot:run

Application URL: http://localhost:8080

Health endpoint: http://localhost:8080/actuator/health

Configure database credentials using environment variables. Do not commit real passwords:

- `LEDGER_DB_URL`
- `LEDGER_DB_USER`
- `LEDGER_DB_PASSWORD`
- `LEDGER_FLYWAY_URL`
- `LEDGER_FLYWAY_USER`
- `LEDGER_FLYWAY_PASSWORD`

## Run Tests

Run the full build and test lifecycle:

    .\mvnw.cmd clean verify

Testcontainers starts a temporary PostgreSQL container for integration tests. Docker must be running.

## Database Migrations and Role Bootstrap

Flyway applies versioned migrations automatically at application startup. Migration files are under `src/main/resources/db/migration`.

Never modify a migration that has already been applied. Create a new versioned migration for subsequent schema changes.

Before applying V2 to a database, a trusted PostgreSQL administrator must run `scripts/bootstrap-ledger-roles.sql`. This script creates the restricted, non-login `ledger_reversal_approver` role if absent and rejects an existing role with unsafe attributes. It does not grant membership to any login.

After reviewing the intended approval principal, the administrator must separately grant membership to the approved login role. Replace the placeholder with the actual database role name:

    GRANT ledger_reversal_approver TO approved_approver_login;

Do not grant this membership to the ordinary application runtime principal. Approval authority must belong only to a specifically authorized principal. Provision the role and its membership before starting the application against a database that has not yet applied V2.

Do not apply V2 to a persistent database until its role configuration, extension dependencies, migration plan, and backup/recovery procedure have been verified.

## Continuous Integration

GitHub Actions runs the Maven verification lifecycle on pushes and pull requests targeting `main`.

Workflow: `.github/workflows/ci.yml`

## Stop PostgreSQL

Stop the local database:

    docker compose down

To also delete its persisted development database volume:

    docker compose down -v

**Warning:** The second command deletes the local database volume and its data. Do not run it against a database containing data you need.

## Project Roadmap

- Phase 0: Engineering foundation
- Phase 1: Accounting domain and data model
- Phase 2: Double-entry posting engine
- Phase 3: Wallets and transfers
- Phase 4: Settlement and reconciliation
- Phase 5: Correctness under pressure
- Phase 6: Production readiness and deployment
