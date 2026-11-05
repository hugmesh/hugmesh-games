#!/usr/bin/env bash
# docker/postgres-replica-init.sh — first-boot step on the PRIMARY when the
# replica overlay is active (mounted as /docker-entrypoint-initdb.d/02-replication.sh
# by docker-compose.replica.yml).
#
# Creates the `replicator` role with the password in $REPLICATOR_PASSWORD,
# which the overlay REQUIRES (`${REPLICATOR_PASSWORD:?}`). The SQL lives in
# docker/postgres-replica-init.sql and receives the value as the psql
# variable :replicator_password; the value is in no committed file.
#
# Works whether the postgres image's entrypoint sources this file (mode 0644,
# the committed mode) or executes it (mode 0755): either way REPLICATOR_PASSWORD,
# POSTGRES_USER and POSTGRES_DB are in the environment, and the temporary
# init-time server answers on the local socket with no password prompt.
set -euo pipefail

if [ -z "${REPLICATOR_PASSWORD:-}" ]; then
  echo "postgres-replica-init: REPLICATOR_PASSWORD is empty or unset; refusing to create the replicator role" >&2
  exit 1
fi

psql -v ON_ERROR_STOP=1 --no-password --no-psqlrc \
  --username "${POSTGRES_USER}" --dbname "${POSTGRES_DB}" \
  -v replicator_password="${REPLICATOR_PASSWORD}" \
  -f /docker/postgres-replica-init.sql
