# Decision 022 — Postgres read replica + 2-CF routing

**Status**: Accepted 2026-05-12. The BFF-side config landed in `feature/postgres-read-replica`; the compose-level replica landed later as an overlay (see Follow-ups). No service reads from the replica yet: the per-service migration below has not started.

## Context

`/api/games/*` + `/api/chapters/*` + `/api/federation/*` are the heaviest catalog-read paths. Today they hit the same primary Postgres as every write. The platform's data-architect-overview chapter listed catalog reads as one of four explicit cloud-relocation swap-points; this is F3 from the consolidated post-docs plan.

Two infrastructure decisions were confirmed by the user:

- **Deployment**: Bitnami `postgres-replica` container with streaming replication (same shape as hugmesh-id-gateway). Superseded: the overlay uses `postgres:16-alpine` (see Follow-ups).
- **Routing**: Two R2DBC `ConnectionFactory` beans + `@Qualifier` on repos / services. Explicit type-checked at compile time. Same pattern as the existing JDBC + R2DBC coexistence in `JdbcDataSourceConfig`.

## Decision

### BFF side (this PR)

`R2dbcReadReplicaConfig` adds three qualified beans:

- `@Qualifier("replica") ConnectionFactory`
- `@Qualifier("replica") R2dbcEntityTemplate`
- `@Qualifier("replica") DatabaseClient`

The whole config class is conditional on `app.r2dbc.replica.url` being non-empty (`@Conditional(NonEmptyReplicaUrl)`). With the URL **empty** (the default), the class does not load and no `replica` beans exist. The "alias the primary" branch inside `replicaConnectionFactory` is therefore dead in a running app, and a service that injects `@Qualifier("replica")` fails to start in a single-DB deployment. Spring's `@ConditionalOnProperty` would match an empty value, which is why the class uses a custom condition. When the URL is set, the qualified beans build a separate `ConnectionFactory` against it.

Call-site pattern:

```java
public CatalogService(R2dbcEntityTemplate template,
                      @Qualifier("replica") R2dbcEntityTemplate replicaTemplate) {
    this.template        = template;          // writes
    this.replicaTemplate = replicaTemplate;   // reads
}
```

### Compose side (deferred follow-up)

The existing `postgres:16-alpine` container uses the official Postgres image. Switching to Bitnami's `postgresql` image for streaming replication is a breaking change for existing `pgdata:/var/lib/postgresql/data` volumes (Bitnami's path layout differs). The compose-level replica will land as a separate PR that:

1. Renames the volume or runs a one-shot `pg_dumpall` → restore migration.
2. Adds `postgres-primary` (Bitnami, with `POSTGRESQL_REPLICATION_MODE=master`) + `postgres-replica` (Bitnami, slave mode) services.
3. Updates the existing `postgres` service to be an alias of `postgres-primary` for backwards-compat.

Until then, production sets `APP_R2DBC_REPLICA_URL` to point at a managed-RDS replica (or any external Postgres replica). That only creates the `replica` beans. Reads move only when a service injects the qualified template; no service does today (`grep 'Qualifier("replica")'` over `backend/src/main` finds only the config class).

## Consequences

### Positive

- Catalog reads can move to the replica without touching call-site code beyond annotating services with `@Qualifier("replica")`. (None have moved.)
- Single-DB deployments continue to work while no service uses the qualifier. Once one does, that service needs the replica URL set or it fails at startup.
- Cloud-relocation swap-point: a managed RDS / Cloud SQL replica needs an env-var flip plus the per-service migration below.
- The pattern mirrors the existing JDBC + R2DBC coexistence in `JdbcDataSourceConfig` — engineers familiar with one understand the other.

### Negative

- Per-repo migration: each service that wants to read from the replica must be edited individually. There's no global "all reads to replica" switch (intentionally — write-after-read inside a transaction must stay on the primary, and routing-magic would obscure that).
- Replica lag: reads from the replica are eventually-consistent vs the primary. Acceptable for catalog reads (60-second federation manifest cache already tolerates lag); not for active-session reads (which stay on primary).
- Single-host dev deploys keep using one DB unless the replica overlay is on. No traffic uses the replica until services are migrated, whichever way the replica is provisioned.

### Neutral

- AbstractRoutingConnectionFactory was rejected in favour of explicit qualifiers — failure mode is louder this way (compile error vs silent routing to wrong DB).

## Implementation

| File | Purpose |
|---|---|
| [`backend/src/main/java/games/hugmesh/bff/config/R2dbcReadReplicaConfig.java`](../../../backend/src/main/java/games/hugmesh/bff/config/R2dbcReadReplicaConfig.java) | The three qualified beans + fallback-to-primary logic. |
| `application.yml` | `app.r2dbc.replica.{url,username,password}` config block. |
| [`backend/src/test/java/games/hugmesh/bff/config/R2dbcReadReplicaConfigTest.java`](../../../backend/src/test/java/games/hugmesh/bff/config/R2dbcReadReplicaConfigTest.java) | 4 tests that call the bean methods directly: empty and blank URL return the primary CF, and the template and client use the replica CF. They do not boot the conditional. |

## Migration plan (per repo / service)

1. Pick a read-only service (start with `GameService` or `FederationController`).
2. Inject the `@Qualifier("replica") R2dbcEntityTemplate` alongside the existing template. The qualified bean exists only when `app.r2dbc.replica.url` is non-empty, so every environment that runs the service must set it (to the primary's own URL in a single-DB setup).
3. Switch the read methods to the qualified template; leave the write methods on the primary.
4. Add an integration test that hits both endpoints — pinning that reads work even when the replica URL is set to the primary (alias mode) AND when set to a separate CF.
5. Repeat for each read-heavy service.

## Follow-ups

- ~~Compose-level Bitnami primary + replica pair~~ — done. The compose-level pair landed as an OPT-IN OVERLAY at [`docker-compose.replica.yml`](../../../docker-compose.replica.yml) using the existing `postgres:16-alpine` image for BOTH primary and replica (no Bitnami swap — keeps the existing `pgdata` volume layout). Activation flow + smoke test in [`scripts/setup-pg-replica.sh`](../../../scripts/setup-pg-replica.sh): bounces primary with `wal_level=replica` + replication role + mounted `pg_hba.conf`, brings the replica up which `pg_basebackup`s on first boot via [`docker/postgres-replica-entrypoint.sh`](../../../docker/postgres-replica-entrypoint.sh), then writes a tag to primary and reads it back from replica to prove streaming is live.
- Snapshot of migrated services in `CLAUDE.md` once the catalog repos are switched.
- Decision-023 if production picks a managed replica that needs a different routing strategy (e.g., pgpool-II in front of both).
