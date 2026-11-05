# 002 — R2DBC + JDBC coexistence in one BFF

**Status:** Adopted

## Decision

The BFF runs two connection pools against the same Postgres instance: R2DBC for runtime queries (non-blocking) and JDBC for Liquibase (blocking).

## Why

WebFlux needs non-blocking IO on the dispatcher thread. Liquibase has no reactive driver and never will; its API blocks on statement execution and lock acquisition. Running migrations out of process would need a separate init container and would split the deploy artefact in two. Liquibase dominates the BFF's startup cost either way, so we keep it in process.

## How to apply

- Spring's auto-config disables `DataSourceAutoConfiguration` when an R2DBC `ConnectionFactory` is present. So [`JdbcDataSourceConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/JdbcDataSourceConfig.java) declares an explicit `@Primary HikariDataSource` with pool size 3, used only for the migration phase.
- R2DBC connects in parallel to the same database through `spring.r2dbc.url`.
- The native-image overlay is the exception. `docker-compose.native.yml` runs Liquibase in a separate `liquibase/liquibase:4.29` init container and sets `SPRING_LIQUIBASE_ENABLED=false` on the `api` service (see [decision 004](004-graalvm-native.md)).
- `@PostConstruct` services that touch the database **must** carry `@DependsOn("liquibase")`. Without it they can run before Liquibase commits and lock their R2DBC connection into a pre-migration MVCC snapshot. `GameStateService`, `GameTimeService` and `DebugModeService` carry the annotation. The symptom of forgetting it is `BadSqlGrammarException: relation does not exist` from a query the test fixture clearly sets up.
- Integration tests that use a real database set `spring.r2dbc.pool.initial-size=0` (for example `GameUploadIntegrationTest`), so the pool opens connections only after Liquibase finishes.

## Trade-offs accepted

- **Two pools' worth of memory.** Hikari pool 3 plus R2DBC pool 10 (Spring Boot's default `initialSize`) is 13 baseline connections on a small instance. No admin panel shows pool counts.
- **Two URL configs to keep in sync.** `spring.datasource.url` and `spring.r2dbc.url` look alike but differ in protocol prefix (`jdbc:postgresql://` vs `r2dbc:postgresql://`). Keep both keys in one hand-edited config file to avoid drift.

## Reference

- [04 — Data model](../architecture/04-data-model.md#r2dbc--jdbc-coexistence)
- [`backend/src/main/java/games/hugmesh/bff/config/JdbcDataSourceConfig.java`](../../../backend/src/main/java/games/hugmesh/bff/config/JdbcDataSourceConfig.java)
