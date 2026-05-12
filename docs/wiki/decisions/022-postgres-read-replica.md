# Decision 022 — Postgres read replica + 2-CF routing

**Status**: Accepted 2026-05-12. BFF-side routing lands in `feature/postgres-read-replica`; compose-level replica container deferred to a follow-up that doesn't break existing volumes.

## Context

`/api/games/*` + `/api/chapters/*` + `/api/federation/*` are the heaviest catalog-read paths. Today they hit the same primary Postgres as every write. The platform's data-architect-overview chapter listed catalog reads as one of four explicit cloud-relocation swap-points; this is F3 from the consolidated post-docs plan.

The four infrastructure decisions confirmed by the user:

- **Deployment**: Bitnami `postgres-replica` container with streaming replication (same shape as snapdragon-id-gateway).
- **Routing**: Two R2DBC `ConnectionFactory` beans + `@Qualifier` on repos / services. Explicit type-checked at compile time. Same pattern as the existing JDBC + R2DBC coexistence in `JdbcDataSourceConfig`.

## Decision

### BFF side (this PR)

`R2dbcReadReplicaConfig` adds three qualified beans:

- `@Qualifier("replica") ConnectionFactory`
- `@Qualifier("replica") R2dbcEntityTemplate`
- `@Qualifier("replica") DatabaseClient`

When `app.r2dbc.replica.url` is **empty** (the default), all three beans alias the `@Primary` CF — services already wired with the qualifier keep working in single-DB deployments. When the replica URL is set, the qualified beans build a separate `ConnectionFactory` against it.

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

Until then, production sets `APP_R2DBC_REPLICA_URL` env var to point at a managed-RDS replica (or any external Postgres replica); the BFF routes transparently.

## Consequences

### Positive

- Catalog reads can move to the replica without touching call-site code beyond annotating services with `@Qualifier("replica")`.
- Single-DB deployments continue to work — the qualifier resolves to the primary.
- Cloud-relocation swap-point closed: managed RDS / Cloud SQL replicas need only an env-var flip.
- The pattern mirrors the existing JDBC + R2DBC coexistence in `JdbcDataSourceConfig` — engineers familiar with one understand the other.

### Negative

- Per-repo migration: each service that wants to read from the replica must be edited individually. There's no global "all reads to replica" switch (intentionally — write-after-read inside a transaction must stay on the primary, and routing-magic would obscure that).
- Replica lag: reads from the replica are eventually-consistent vs the primary. Acceptable for catalog reads (60-second federation manifest cache already tolerates lag); not for active-session reads (which stay on primary).
- The compose-level replica container is deferred — single-host dev deploys keep using one DB. Production gets the benefit immediately via `APP_R2DBC_REPLICA_URL` against an external replica.

### Neutral

- AbstractRoutingConnectionFactory was rejected in favour of explicit qualifiers — failure mode is louder this way (compile error vs silent routing to wrong DB).

## Implementation

| File | Purpose |
|---|---|
| [`backend/src/main/java/games/snapdragon/bff/config/R2dbcReadReplicaConfig.java`](../../../backend/src/main/java/games/snapdragon/bff/config/R2dbcReadReplicaConfig.java) | The three qualified beans + fallback-to-primary logic. |
| `application.yml` | `app.r2dbc.replica.{url,username,password}` config block. |
| [`backend/src/test/java/games/snapdragon/bff/config/R2dbcReadReplicaConfigTest.java`](../../../backend/src/test/java/games/snapdragon/bff/config/R2dbcReadReplicaConfigTest.java) | 4 tests covering the alias-to-primary contract. |

## Migration plan (per repo / service)

1. Pick a read-only service (start with `GameService` or `FederationController`).
2. Inject the `@Qualifier("replica") R2dbcEntityTemplate` alongside the existing template.
3. Switch the read methods to the qualified template; leave the write methods on the primary.
4. Add an integration test that hits both endpoints — pinning that reads work even when the replica URL is set to the primary (alias mode) AND when set to a separate CF.
5. Repeat for each read-heavy service.

## Follow-ups

- Compose-level Bitnami primary + replica pair (separate PR; needs the pgdata-migration story).
- Snapshot of migrated services in `CLAUDE.md` once the catalog repos are switched.
- Decision-023 if production picks a managed replica that needs a different routing strategy (e.g., pgpool-II in front of both).
