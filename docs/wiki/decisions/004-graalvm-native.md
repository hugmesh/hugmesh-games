# 004 — GraalVM native-image as an opt-in build path

**Status:** Adopted; opt-in.

## Decision

The BFF compiles to a GraalVM native binary as an *additional* artefact, not a replacement. The fat JAR stays the default. The native image is opt-in through `make build-backend-native` and the `docker-compose.native.yml` overlay.

## Why

The header of `backend/Dockerfile.native` estimates JVM cold start at ~3 s with ~400 MB RSS, and native at ~50–100 ms with ~100 MB RSS. These are estimates, not measurements. The gain helps autoscaling and development cycles where the BFF restarts often.

The cost: native-image builds are slow (~5–10 minutes per the `Makefile`), need ~6–8 GB RAM, and have sharp edges around reflection (records, Jackson generics, Liquibase scope managers, springdoc OpenAPI). We do not want every CI build to pay that price. We do not want a native regression to block JVM-mode shipping.

## How to apply

- **Default: JVM build.** `make build-backend` produces the fat JAR. The `test:backend` job in `.gitlab-ci.yml` runs `./mvnw -B test`. No CI job builds the native image.
- **Opt-in: native build.** `make build-backend-native` runs `docker build -f backend/Dockerfile.native`. That Dockerfile runs `./mvnw -Pnative -DskipTests native:compile`, the `native` profile in [`backend/pom.xml`](../../../backend/pom.xml). Nothing gates it in CI yet.
- **Reflection hints in one place.** [`HugMeshRuntimeHints`](../../../backend/src/main/java/games/hugmesh/bff/aot/HugMeshRuntimeHints.java), wired by `@ImportRuntimeHints` on the application class, holds the hints: the JSON-boundary DTOs, the `assets/.*` classpath resources, and Netty's jctools field classes. A new DTO touches this one file. [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/hugmesh/bff/config/R2dbcConvertersConfig.java) is not a hint file. It registers two timestamp converters so `Instant` columns convert the same way in JVM and native mode.
- **DTOs across JSON.** Add every DTO that crosses the JSON boundary to `JSON_BOUNDARY_DTOS` in `HugMeshRuntimeHints` (13 today, among them `CharacterDto`, `GameWithChaptersDto`, `SceneDto`). The list omits the SSE event records. The code does not use `@RegisterReflectionForBinding`.
- **Dockerfile is parallel, not replacing.** [`backend/Dockerfile.native`](../../../backend/Dockerfile.native) is its own multi-stage build. The non-native [`backend/Dockerfile`](../../../backend/Dockerfile) is unchanged.
- **Compose overlay.** `make e2e-native` runs the e2e compose files plus `docker-compose.native.yml`. The overlay keeps the service name `api` and points its build at `Dockerfile.native` (image `hugmesh-gaming-bff:native`). It also adds a `liquibase` init service.

## Trade-offs accepted

- **No class reload in dev.** Native is for production and e2e, not for the inner loop.
- **Slow first build.** ~5–10 minutes; ~6–8 GB RAM peak. CI runners may need more memory if native ever runs on every PR.
- **Springdoc OpenAPI quirks.** `@Operation` reflection has caused native-image trouble. The `pom.xml` now pins springdoc 3.0.3, not the 2.7.0 this record once named. If `/v3/api-docs` regresses under native, the symptom is a startup `ClassNotFoundException` for an `@Operation`-annotated bean.
- **Liquibase runs outside the native binary.** The fallback this record held in reserve is the shipped design. In `docker-compose.native.yml` a separate `liquibase/liquibase:4.29` container runs `update` first, and the `api` service sets `SPRING_LIQUIBASE_ENABLED=false`. The JVM build still runs Liquibase in process.

## Reference

- [06 — Build and deploy](../architecture/06-build-and-deploy.md#graalvm-native-image)
- Plan section D in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
