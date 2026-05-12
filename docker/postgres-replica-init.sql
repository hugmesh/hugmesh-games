-- Postgres replication setup — applied to the PRIMARY on first boot
-- when the replica overlay is active (decision 022 follow-up).
--
-- This file ONLY creates the `replicator` role. The pg_hba.conf
-- entry for the streaming connection lives in
-- `docker/postgres-pg-hba.conf` which the overlay mounts as a
-- bind volume; that's the right place for auth rules + avoids the
-- pg_read_file / COPY TO PROGRAM dance.
--
-- Idempotent — re-runs against an already-configured primary skip
-- the role creation via the DO block guard.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'replicator') THEN
        CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replicator';
    END IF;
END $$;
