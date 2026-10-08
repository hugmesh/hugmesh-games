# 004 — GraalVM native-image as an opt-in build path

**Status:** Adopted; opt-in.

## Decision

The BFF compiles to a GraalVM native binary as an *additional* artefact, not as a replacement. The fat JAR is still the default; the native image is opt-in via `make build-backend-native` and the `docker-compose.native.yml` overlay.

## Why

The header of `backend/Dockerfile.native` estimates JVM cold start at ~3 s with ~400 MB RSS, and native at ~50–100 ms with ~100 MB RSS. They are estimates, not measurements. The gain is useful for autoscaling and for development cycles where the BFF restarts frequently.

But native-image builds are slow (~5–10 minutes per the `Makefile`), need ~6–8 GB RAM, and have known sharp edges around reflection (records, Jackson generics, Liquibase scope managers, springdoc OpenAPI). We don't want every CI build to pay that cost, and we don't want a regression in native to block JVM-mode shipping.

## How to apply

- **Default: JVM build.** `make build-backend` produces the fat JAR. The `test:backend` job in `.gitlab-ci.yml` runs `./mvnw -B test`; no CI job builds the native image.
- **Opt-in: native build.** `make build-backend-native` runs `docker build -f backend/Dockerfile.native`, which runs `./mvnw -Pnative -DskipTests native:compile`, the `native` profile in [`backend/pom.xml`](../../../backend/pom.xml). Nothing gates it in CI yet.
- **Reflection hints in one place.** [`HugMeshRuntimeHints`](../../../backend/src/main/java/games/hugmesh/bff/aot/HugMeshRuntimeHints.java), wired by `@ImportRuntimeHints` on the application class, holds the hints: the JSON-boundary DTOs, the `assets/.*` classpath resources, and Netty's jctools field classes. A new DTO touches this one file. [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/R2dbcConvertersConfig.java) is not a hint file; it registers two timestamp converters so `Instant` columns convert the same way in JVM and native mode.
- **DTOs across JSON.** Add every DTO that crosses the JSON boundary to `JSON_BOUNDARY_DTOS` in `HugMeshRuntimeHints` (13 today, among them `CharacterDto`, `GameWithChaptersDto`, `SceneDto`). The list does not include the SSE event records, and the code does not use `@RegisterReflectionForBinding`.
- **Dockerfile is parallel, not replacing.** [`backend/Dockerfile.native`](../../../backend/Dockerfile.native) is its own multi-stage. The non-native [`backend/Dockerfile`](../../../backend/Dockerfile) is unchanged.
- **Compose overlay.** `make e2e-native` runs the e2e compose files plus `docker-compose.native.yml`. The overlay keeps the service name `api` and points its build at `Dockerfile.native` (image `hugmesh-gaming-bff:native`). It also adds a `liquibase` init service.

## Trade-offs accepted

- **No class reload in dev.** Native is for production / e2e, not for inner-loop development.
- **Slow first build.** ~6 minutes; ~6–8 GB RAM peak. CI runners may need bumping if we ever start running native on every PR.
- **Springdoc OpenAPI quirks.** `@Operation` reflection has caused native-image trouble. The `pom.xml` now pins springdoc 3.0.3, not the 2.7.0 this record once named. If `/v3/api-docs` regresses under native, the symptom is a startup `ClassNotFoundException` for an `@Operation`-annotated bean.
- **Liquibase runs outside the native binary.** The fallback this record held in reserve is the shipped design. In `docker-compose.native.yml` a separate `liquibase/liquibase:4.29` container runs `update` first, and the `api` service sets `SPRING_LIQUIBASE_ENABLED=false`. The JVM build still runs Liquibase in-process.

## Reference

- [06 — Build and deploy](../architecture/06-build-and-deploy.md#graalvm-native-image)
- Plan section D in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
