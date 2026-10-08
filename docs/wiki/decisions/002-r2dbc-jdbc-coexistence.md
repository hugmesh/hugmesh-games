# 002 — R2DBC + JDBC coexistence in one BFF

**Status:** Adopted

## Decision

The BFF runs both R2DBC (runtime, non-blocking) and JDBC (Liquibase, blocking) connection pools against the same Postgres instance.

## Why

WebFlux requires non-blocking IO on the dispatcher thread. Liquibase has no reactive driver and never will — its API is intrinsically blocking around statement execution and lock acquisition. Running migrations out-of-process would require a separate init container and would split the deploy artefact in two; given the BFF's startup cost is dominated by Liquibase regardless, we keep it in-process.

## How to apply

- Spring's auto-config disables `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` is present, so [`JdbcDataSourceConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/JdbcDataSourceConfig.java) declares an explicit `@Primary HikariDataSource` of pool size 3 just for the migration phase.
- R2DBC connects in parallel against the same database via `spring.r2dbc.url`.
- The native-image overlay is the exception. `docker-compose.native.yml` runs Liquibase in a separate `liquibase/liquibase:4.29` init container and sets `SPRING_LIQUIBASE_ENABLED=false` on the `api` service (see [decision 004](004-graalvm-native.md)).
- `@PostConstruct` services that touch the database **must** carry `@DependsOn("liquibase")`. Without it they can run before Liquibase commits, locking their R2DBC connection into a pre-migration MVCC snapshot. `GameStateService`, `GameTimeService` and `DebugModeService` carry this annotation; symptom of forgetting is `BadSqlGrammarException: relation does not exist` from a query the test fixture clearly sets up.
- Integration tests that run against a real database set `spring.r2dbc.pool.initial-size=0` (for example `GameUploadIntegrationTest`), so the pool only opens connections after Liquibase finishes.

## Trade-offs accepted

- **Two pools' worth of memory.** Hikari pool 3 + R2DBC pool 10 (Spring Boot's default `initialSize`) = 13 baseline connections on a small instance. No admin panel shows pool counts.
- **Two URL configs to keep in sync.** `spring.datasource.url` and `spring.r2dbc.url` look superficially identical but differ in protocol prefix (`jdbc:postgresql://` vs `r2dbc:postgresql://`). One hand-edited config file with both keys avoids drift.

## Reference

- [04 — Data model](../architecture/04-data-model.md#r2dbc--jdbc-coexistence)
- [`backend/src/main/java/games/hugmesh/bff/config/JdbcDataSourceConfig.java`](../../../backend/src/main/java/games/hugmesh/bff/config/JdbcDataSourceConfig.java)
