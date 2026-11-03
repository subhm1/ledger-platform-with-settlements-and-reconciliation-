#!/usr/bin/env bash
set -euo pipefail

psql \
  --username "$POSTGRES_USER" \
  --dbname "$POSTGRES_DB" \
  --set=ON_ERROR_STOP=1 \
  --set=runtime_password="$LEDGER_DB_PASSWORD" \
  --set=migrator_password="$LEDGER_FLYWAY_PASSWORD" \
  --file=/usr/local/share/ledger/local-review-roles.sql
