# 004 — GraalVM native-image as an opt-in build path

**Status:** Adopted; opt-in.

## Decision

The BFF compiles to a GraalVM native binary as an *additional* artefact, not as a replacement. The fat JAR is still the default; the native image is opt-in via `make build-backend-native` and the `compose.native.yml` overlay.

## Why

JVM cold start of the BFF is ~3 s with ~400 MB RSS. Native image gets that to <100 ms with ~100 MB RSS — useful for autoscaling and for development cycles where the BFF restarts frequently.

But native-image builds are slow (~6 minutes), need ~6–8 GB RAM, and have known sharp edges around reflection (records, Jackson generics, Liquibase scope managers, springdoc OpenAPI). We don't want every CI build to pay that cost, and we don't want a regression in native to block JVM-mode shipping.

## How to apply

- **Default: JVM build.** `make build-backend` produces the fat JAR. CI runs this on every PR.
- **Opt-in: native build.** `make build-backend-native` invokes the `native` Maven profile in [`backend/pom.xml`](../../../backend/pom.xml). Gate this behind `[native]` in commit message OR a separate workflow that runs on `main`.
- **Reflection hints in one place.** [`R2dbcConvertersConfig`](../../../backend/src/main/java/games/snapdragon/bff/config/R2dbcConvertersConfig.java) registers every R2DBC entity for native-image reflection. Adding a new entity touches one file.
- **DTOs across JSON.** Use `@RegisterReflectionForBinding` on every DTO that crosses the JSON boundary (`CharacterDto`, `GameWithLevelsDto`, `SceneDto`, all SSE event records).
- **Dockerfile is parallel, not replacing.** [`backend/Dockerfile.native`](../../../backend/Dockerfile.native) is its own multi-stage. The non-native [`backend/Dockerfile`](../../../backend/Dockerfile) is unchanged.
- **Compose overlay.** `make e2e-native` runs against `compose.yml + compose.native.yml`. The overlay swaps `api` → `api-native`.

## Trade-offs accepted

- **No class reload in dev.** Native is for production / e2e, not for inner-loop development.
- **Slow first build.** ~6 minutes; ~6–8 GB RAM peak. CI runners may need bumping if we ever start running native on every PR.
- **Springdoc OpenAPI quirks.** Springdoc 2.7.0 has known native-image issues around `@Operation` reflection. Verified `/v3/api-docs` resolves under native; if it regresses, the symptom is a startup `ClassNotFoundException` for an `@Operation`-annotated bean.
- **Liquibase scope manager.** Liquibase 4.27+ has reasonable native support but may need `liquibase.scope.SingletonScopeManager` hints. If we hit blockers, run migrations OUT-OF-PROCESS (separate init container) instead — cleaner anyway, but a bigger change than we want to absorb today.

## Reference

- [06 — Build and deploy](../architecture/06-build-and-deploy.md#graalvm-native-image)
- Plan section D in [`docs/PLAN-module-federation-and-native.md`](../../PLAN-module-federation-and-native.md).
