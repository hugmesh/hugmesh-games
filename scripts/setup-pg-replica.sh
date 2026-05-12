#!/usr/bin/env bash
#
# Bootstrap script for the Postgres primary+replica overlay
# (decision 022 follow-up).
#
# Why this exists: the overlay (`docker-compose.replica.yml`) wires
# the replica service AND reconfigures the primary's command-line
# flags + pg_hba.conf. On a FRESH deploy the new compose flow takes
# over and everything just works. But for an EXISTING deploy with a
# populated `pgdata` volume, the primary has already initialised
# with the old (non-replication-aware) config, so the new
# `wal_level=replica` + replication-role + pg_hba.conf rules need
# to be re-applied without losing data.
#
# This script:
#   1. Bounces the primary with the new compose flags (drops the
#      old container; the pgdata volume persists).
#   2. Connects to the running primary, creates the `replicator`
#      role if missing.
#   3. Brings up the replica service which runs pg_basebackup from
#      the primary on first start.
#   4. Smoke-tests the replication by writing a tag row to the
#      primary and reading it back from the replica.
#
# IDEMPOTENT — safe to re-run. Skips role creation if it already
# exists; skips pg_basebackup if the replica's volume is already
# populated.

set -euo pipefail

cd "$(dirname "$(realpath "$0")")/.."

echo "→ Bring up primary + replica with the replica overlay"
docker compose -f docker-compose.yml -f docker-compose.replica.yml up -d postgres
echo

echo "→ Wait for primary to be healthy"
for i in $(seq 1 30); do
    if docker compose exec -T postgres pg_isready -U threewizards >/dev/null 2>&1; then
        echo "  primary ready (attempt $i)"
        break
    fi
    sleep 2
done

# The replication role creation runs from postgres-replica-init.sql
# at first-boot only. For EXISTING volumes (postgres-init.sql ran
# previously), we apply it manually.
echo "→ Ensure replicator role exists"
docker compose exec -T postgres psql -U threewizards -d threewizards -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator') THEN
        CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replicator';
        RAISE NOTICE 'created replicator role';
    ELSE
        RAISE NOTICE 'replicator role already exists';
    END IF;
END $$;
SQL
echo

echo "→ Bring up replica (pg_basebackup runs on first start)"
docker compose -f docker-compose.yml -f docker-compose.replica.yml up -d postgres-replica
echo

echo "→ Wait for replica to enter hot-standby"
for i in $(seq 1 60); do
    if docker compose exec -T postgres-replica pg_isready -U threewizards >/dev/null 2>&1; then
        echo "  replica ready (attempt $i)"
        break
    fi
    sleep 2
done
echo

echo "→ Smoke test — write a tag on primary, read from replica"
TAG="repl-test-$(date +%s)"
docker compose exec -T postgres psql -U threewizards -d threewizards \
    -c "CREATE TABLE IF NOT EXISTS _replication_smoke(t text); INSERT INTO _replication_smoke VALUES ('$TAG');"

# Replication is async; give it a beat.
sleep 2

if docker compose exec -T postgres-replica psql -U threewizards -d threewizards \
        -tA -c "SELECT t FROM _replication_smoke WHERE t='$TAG';" | grep -q "$TAG"; then
    echo "  ✓ replica saw the tag — replication is live"
else
    echo "  ✗ replica did NOT see the tag — check pg_stat_replication on primary"
    docker compose exec -T postgres psql -U threewizards -d threewizards -c "SELECT * FROM pg_stat_replication;"
    exit 1
fi

# Clean up smoke-test rows (primary deletes; replica replays).
docker compose exec -T postgres psql -U threewizards -d threewizards \
    -c "DROP TABLE IF EXISTS _replication_smoke;" >/dev/null

echo
echo "→ Done. The BFF (api service) is automatically configured by the"
echo "  overlay to point APP_R2DBC_REPLICA_URL at the replica; restart it"
echo "  to apply:"
echo
echo "    docker compose -f docker-compose.yml -f docker-compose.replica.yml up -d --force-recreate api"
