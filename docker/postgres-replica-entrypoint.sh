#!/usr/bin/env bash
#
# Postgres-replica entrypoint (decision 022 follow-up).
#
# On the FIRST boot of the replica container, the pgdata_replica
# volume is empty — we need to bootstrap it from the primary via
# `pg_basebackup`. On every subsequent boot, the volume already
# carries a primary's PGDATA + the `standby.signal` file we drop
# during bootstrap, so Postgres just resumes streaming where it
# left off.
#
# The wait-for-primary loop covers the depends_on race where the
# primary is "healthy" but pg_basebackup briefly errors with
# "the database system is starting up" — retry a few times before
# giving up.

set -e

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
PRIMARY_HOST="${PRIMARY_HOST:-postgres}"
PRIMARY_PORT="${PRIMARY_PORT:-5432}"
REPLICATOR_USER="${REPLICATOR_USER:-replicator}"
REPLICATOR_PASSWORD="${REPLICATOR_PASSWORD:-replicator}"

# Bootstrap-only path: empty PGDATA → pg_basebackup from primary.
if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "[replica-entrypoint] PGDATA empty — bootstrapping from primary $PRIMARY_HOST:$PRIMARY_PORT"

    # Ensure ownership before pg_basebackup writes here; the official
    # image runs initdb as the `postgres` user.
    mkdir -p "$PGDATA"
    chown -R postgres:postgres "$PGDATA"
    chmod 700 "$PGDATA"

    # Retry pg_basebackup — primary's healthcheck may pass before the
    # walsender slot is reachable, especially right after first boot.
    attempts=0
    until su-exec postgres env PGPASSWORD="$REPLICATOR_PASSWORD" \
            pg_basebackup \
                --host="$PRIMARY_HOST" --port="$PRIMARY_PORT" \
                --username="$REPLICATOR_USER" \
                --pgdata="$PGDATA" \
                --wal-method=stream \
                --progress --verbose \
                --write-recovery-conf; do
        attempts=$((attempts + 1))
        if [ $attempts -ge 30 ]; then
            echo "[replica-entrypoint] pg_basebackup failed after 30 attempts; giving up" >&2
            exit 1
        fi
        echo "[replica-entrypoint] pg_basebackup attempt $attempts failed — retrying in 2s"
        sleep 2
    done

    # --write-recovery-conf already wrote standby.signal +
    # primary_conninfo. Nothing else to do.
    echo "[replica-entrypoint] base backup complete; starting in standby mode"
else
    echo "[replica-entrypoint] PGDATA already populated — resuming standby replay"
fi

# Hand off to the official postgres entrypoint, which honours
# `standby.signal` and starts in hot-standby mode automatically.
exec docker-entrypoint.sh "$@"
