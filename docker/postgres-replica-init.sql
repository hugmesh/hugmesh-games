-- Postgres replication setup — the `replicator` role.
--
-- Run by docker/postgres-replica-init.sh on the PRIMARY's first boot when the
-- replica overlay is active, and by scripts/setup-pg-replica.sh against an
-- EXISTING primary. Both pass the role's password as the psql variable
-- :replicator_password, taken from $REPLICATOR_PASSWORD in the container's
-- environment (docker-compose.replica.yml requires it). The password is
-- never written in this file.
--
-- Why not a DO block: psql does not substitute :variables inside a
-- dollar-quoted body, so the only way to get the value in is to build the
-- statement with format() (%L quotes it as a literal) and run it with \gexec.
--
-- The pg_hba.conf entry for the streaming connection lives in
-- `docker/postgres-pg-hba.conf`, which the overlay mounts; that is the
-- right place for auth rules.
--
-- Idempotent, exactly one statement per run: a missing role is created, an
-- existing one has its password set to the configured value (so an operator
-- who rotates REPLICATOR_PASSWORD re-runs the setup script, and the
-- replica's entrypoint, which uses the same variable, can still connect).

\set ON_ERROR_STOP on

SELECT format(
  CASE WHEN EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator')
       THEN 'ALTER ROLE replicator WITH PASSWORD %L'
       ELSE 'CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD %L'
  END,
  :'replicator_password')
\gexec
