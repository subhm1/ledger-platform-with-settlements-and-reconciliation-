# Ledger Platform

A production-oriented double-entry ledger, settlement, and reconciliation platform built with Java 21, Spring Boot, PostgreSQL, and Flyway.

## Current Status

Phase 0 — Engineering Foundation.

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

Local development database settings:

- Database: `ledger`
- Username: `ledger_app`
- Port: `5432`

The development password is configured in `compose.yaml`. Do not reuse these credentials in production.

## Run the Application

Start the application:

    .\mvnw.cmd spring-boot:run

Application URL: http://localhost:8080

Health endpoint: http://localhost:8080/actuator/health

## Run Tests

Run the full build and test lifecycle:

    .\mvnw.cmd clean verify

Testcontainers starts a temporary PostgreSQL container for integration tests. Docker must be running.

## Database Migrations

Flyway applies versioned migrations automatically at application startup.

Migration directory: `src/main/resources/db/migration`

Do not modify migrations that have already been applied. Create a new versioned migration for subsequent schema changes.

## Continuous Integration

GitHub Actions runs the Maven verification lifecycle on pushes and pull requests targeting `main`.

Workflow: `.github/workflows/ci.yml`

## Stop PostgreSQL

Stop the local database:

    docker compose down

To also delete its persisted development database volume:

    docker compose down -v

**Warning:** The second command deletes the local database volume and its data.

## Project Roadmap

- Phase 0: Engineering foundation
- Phase 1: Accounting domain and data model
- Phase 2: Double-entry posting engine
- Phase 3: Wallets and transfers
- Phase 4: Settlement and reconciliation
- Phase 5: Correctness under pressure
- Phase 6: Production readiness and deployment
