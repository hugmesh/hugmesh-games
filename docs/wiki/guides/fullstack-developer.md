# Full-stack developer guide

> Mirror of [`docs/publishing/userguides/fullstack-developer/guide.tex`](../../publishing/userguides/fullstack-developer/guide.tex).

DB to browser, with the rules that hold the federated stack together.

## Mental model

One repository, three languages, four runtimes. The BFF is Java 21 + Spring Boot 3.4 + WebFlux + R2DBC + JDBC. The frontend is TypeScript + Angular standalone components + Module Federation. The games are GDScript + Godot 4.6, with a Rust GDExtension shared across them. The GraalVM native AOT build of the BFF is the same Java code with a sub-second cold start.

## How requests are shaped

The browser hits the host nginx; the host routes to the matching per-remote nginx for `/_remotes/<slug>/...` and to the BFF for `/api/...`. Inside the BFF every controller is a `@RestController`; routing is annotation-based, and WebFlux decides reactor vs blocking by inspecting return types. Liquibase runs once at boot on a JDBC pool of size 3 (separate from the R2DBC runtime pool) — see [decision 002](../decisions/002-r2dbc-jdbc-coexistence.md).

## Backend tour, in five files

| File | Why it's load-bearing |
|---|---|
| [`GameStreamController`](../../../backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java) | The SSE merge that drives the live HUD. The `autoCancel=false` rule lives here. |
| [`GameUploadService`](../../../backend/src/main/java/games/snapdragon/bff/service/GameUploadService.java) | The install transaction. Understanding this gives the whole hot-upload story. |
| [`FederationController`](../../../backend/src/main/java/games/snapdragon/bff/controller/FederationController.java) | The manifest endpoint that drives the host's import map. Trivial code, foundational consequence. |
| [`JdbcDataSourceConfig`](../../../backend/src/main/java/games/snapdragon/bff/config/JdbcDataSourceConfig.java) | The one-line `@Primary HikariDataSource` that explains R2DBC + JDBC coexistence. |
| [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/snapdragon/bff/config/R2dbcConvertersConfig.java) | Registers every entity for native-image reflection. Adding a new entity touches one place. |

## Reactive vs blocking

WebFlux requires non-blocking IO on the dispatcher thread. R2DBC for the runtime path; JDBC + Liquibase only at boot. If a chain needs to call something blocking, wrap it:

```java
Mono.fromCallable(() -> blockingCall())
    .subscribeOn(Schedulers.boundedElastic())
```

### ⚠ `Sinks.many()` must set `autoCancel=false`

The default kills the sink on the first reconnect. Long-lived merges go through `Flux.merge` — see [`GameStreamController`](../../../backend/src/main/java/games/snapdragon/bff/controller/GameStreamController.java) for the canonical 8-channel pattern. See [decision 003](../decisions/003-autocancel-false.md).

## Adding a federated route

1. Create `frontend/projects/<remote>/` with its own `federation.config.js`.
2. Expose the entry component in the `exposes` map.
3. Insert a `federation_modules` row (Liquibase changeset). `kind=remote-shell` for full route components.
4. Add a `loadRemote(...)` entry to [`frontend/src/app/app.routes.ts`](../../../frontend/src/app/app.routes.ts).
5. Add a `/_remotes/<remote>/` proxy block to [`frontend/nginx.conf`](../../../frontend/nginx.conf).
6. Add the per-remote service to [`docker-compose.yml`](../../../docker-compose.yml).
7. Redeclare DTOs inside the remote — types erase across the federation boundary.

Step-by-step: [howto/add-federated-route.md](../howto/add-federated-route.md).

## Common code-review patterns

- **New endpoint?** Check that [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/snapdragon/bff/config/R2dbcConvertersConfig.java) is updated and `make gen-api` ran.
- **New SSE channel?** Check `Sinks.many(...).onBackpressureBuffer(N, false)` — the `false` is `autoCancel`.
- **New federated remote?** Check the host `nginx.conf`'s `/_remotes/` block AND the `federation_modules` row.
- **New Liquibase changeset?** Next integer prefix and included in [`db.changelog-master.xml`](../../../backend/src/main/resources/db/changelog/db.changelog-master.xml). Never edit a previously-merged changeset.
- **New game?** Four-place slug contract — see the [Godot developer guide](godot-developer.md).
- **Spring Data derived query?** Method name parsed against the entity property tree at startup. After a rename, `findByLevelId` against an entity with `chapterId` produces `PropertyReferenceException`. Rename the method.

## What NOT to do

- Don't call JDBC from a WebFlux controller.
- Don't import `@angular-architects/native-federation` statically from a route handler.
- Don't share TypeScript DTO types across federation boundaries.
- Don't bypass Liquibase. Manual SQL on a live DB diverges.
- Don't add controller logic without tests AND an updated Javadoc.
- Don't `--no-verify` past a pre-commit hook. Investigate the hook failure.
