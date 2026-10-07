# Decision 020 — Object-storage-backed game uploads

**Status**: Accepted 2026-05-12.

**Context**: Game-bundle uploads have always landed on the host filesystem under `app.uploads.games-dir`. That works for a single-host compose-only deploy, but it's the load-bearing constraint that keeps the BFF from horizontally scaling — every replica would need a shared filesystem (NFS, EFS, etc.) or split-brain inconsistency. The data-architect-overview userguide listed this swap-point as one of the four explicit cloud-relocation targets.

## Decision

Introduce an `UploadStorage` strategy interface with two implementations:

- **`FilesystemUploadStorage`** — current behaviour, default. Bundle files land under `<gamesDir>/<slug>/<sha-prefix>/`. No infra change.
- **`S3UploadStorage`** — S3-compatible bucket. Works against AWS S3 in prod, MinIO in dev / CI, any S3-compatible service (Cloudflare R2, Backblaze B2, etc.) in between.

Selected via `app.uploads.storage=filesystem|s3` config (env: `APP_UPLOADS_STORAGE`).

### Locator scheme

`game_uploads.version_dir` (and `games.current_version_dir`) now store an opaque locator the strategy understands:

- `file:<absolute-path>` — filesystem strategy.
- `s3://<bucket>/<slug>/<sha-prefix>` — S3 strategy.

Existing rows that pre-date the prefix scheme (raw absolute paths) keep resolving via `FilesystemUploadStorage`, so no migration is required at flip-flop time.

### Strategy surface

```java
interface UploadStorage {
    String installFromStaging(String slug, String versionPrefix, Path stagingDir);
    Resource read(String locator, String relativePath);
    boolean exists(String locator, String relativePath);
    void deleteVersion(String locator);
    String label();   // "filesystem" / "s3" — surfaced in boot logs

    // default methods:
    Map<String, String> contentTypeOverrides();                         // default: Map.of()
    URI presignReadUrl(String locator, String relativePath, Duration ttl); // default: null
}
```

Three call sites:
- `GameUploadService.writeAndRegister(...)` calls `installFromStaging(...)`.
- `AssetController.serveLevelFile(...)` + `serveFromPinnedDir(...)` call `read(...)`.
- `GameUploadPruner.removeVersionDir(...)` calls `deleteVersion(...)`.

## Consequences

### Positive

- Horizontal scaling is no longer blocked by shared-filesystem requirements.
- `wasm-fragment` content can move behind a CDN by pointing the CDN at the bucket.
- Backup posture improves — S3 lifecycle policies + versioning replace the host-disk snapshot loop.
- The strategy boundary lets us add a third backend (e.g. GCS, Azure Blob) without touching `GameUploadService` or `AssetController`.

### Negative

- Two extra containers in dev (`minio` + `minio-init` one-shot bucket bootstrap). `docker-compose.yml` gives them no profile, so a plain `docker compose up -d` starts them even in the default `app.uploads.storage=filesystem` mode, where nothing uses them. (The comment on the `minio` service says they are opt-in. That is wrong.)
- `AssetController` now goes through a `Resource` rather than a `FileSystemResource` directly — S3 reads bring the whole file into memory via `ByteArrayResource`. Acceptable for game-bundle sizes (manifest+SQL+small bundle/ tree, capped at 200 MB); the hot path is the WASM `.wasm` file which streams once per launch.
- Tests that previously checked filesystem state directly need updating to go through the strategy interface (or to run only against the `filesystem` mode).

### Neutral

- Resilience4j circuit breakers around S3 calls were deferred at first and have since landed (see Follow-ups). The classpath fallback in `AssetController` covers the games shipped in the API image whenever a read returns null.

## Implementation

Files:

- `backend/src/main/java/games/hugmesh/bff/upload/UploadStorage.java` — interface
- `backend/src/main/java/games/hugmesh/bff/upload/FilesystemUploadStorage.java`
- `backend/src/main/java/games/hugmesh/bff/upload/S3UploadStorage.java`
- `backend/src/main/java/games/hugmesh/bff/upload/UploadStorageConfig.java` — bean selection
- `backend/src/main/java/games/hugmesh/bff/service/GameUploadService.java` — strategy call site for writes
- `backend/src/main/java/games/hugmesh/bff/service/GameUploadPruner.java` — strategy call site for deletes
- `backend/src/main/java/games/hugmesh/bff/controller/AssetController.java` — strategy call site for reads
- `backend/pom.xml` — `software.amazon.awssdk:s3` (with `url-connection-client` to avoid clashing with Reactor Netty)
- `docker-compose.yml` — `minio` + `minio-init` services + `minio-data` volume
- `backend/src/main/resources/application.yml` — `app.uploads.storage` + `app.uploads.s3.*` config block

### Verification

```bash
# Filesystem mode (default; no MinIO needed)
docker compose up -d
curl -X POST /api/admin/games/upload -F file=@bundle.zip   # → 200, version_dir = file:/var/hugmesh/games/<slug>/<hash>

# S3 mode
docker compose up -d minio minio-init
APP_UPLOADS_STORAGE=s3 docker compose up -d api
curl -X POST /api/admin/games/upload -F file=@bundle.zip   # → 200, version_dir = s3://hugmesh-uploads/<slug>/<hash>
```

The S3 recipe does not work as written. The `api` service in `docker-compose.yml` forwards `APP_UPLOADS_GAMES_DIR` and nothing else under `APP_UPLOADS_*`, so a shell-level `APP_UPLOADS_STORAGE=s3` never reaches the container. The endpoint (`APP_UPLOADS_S3_ENDPOINT`) has an empty default, and with `s3` selected the BFF refuses to start while `APP_UPLOADS_S3_ACCESS_KEY` or `APP_UPLOADS_S3_SECRET_KEY` is blank. Add the variables to the `api` environment in an overlay first. For the compose MinIO that means the endpoint `http://minio:9000` and the keys `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.

## Follow-ups

- ~~Pre-signed-URL reads (move the WASM `.wasm` hot path off the BFF cold path).~~ — done. `UploadStorage#presignReadUrl(locator, relativePath, ttl)` is the strategy hook; `S3UploadStorage` returns a short-lived AWS-SDK-v2 `S3Presigner` URL (capped at 15 min hard maximum); `FilesystemUploadStorage` returns null (no pre-signing). `AssetController.tryPresignRedirect(...)` mints the URL and 302s when both `app.uploads.s3.presigned-reads=true` AND the strategy returns non-null; otherwise the streaming path is unchanged. Pinned by 2 new `AssetControllerTest` cases (legacy + UID paths). Browser fetches the WASM directly from the bucket once enabled — BFF cold path stays cold.
- ~~Migration script `scripts/migrate-uploads-to-s3.sh`~~ — done. One-shot DRY-RUN-by-default copy of existing host-filesystem uploads into MinIO + `version_dir` column flip.
- ~~Resilience4j circuit breaker around S3 calls~~ — done. `CircuitBreakerUploadStorage` decorator wraps the S3 strategy with the `s3` Resilience4j breaker. Reads soft-fall through to null on OPEN (AssetController has a classpath fallback); writes propagate `UploadStorageException`. Tuning in `application.yml` under `resilience4j.circuitbreaker.instances.s3`. Pinned by 5-case unit test.
- AWS S3 + Cloudflare R2 + Backblaze B2 acceptance matrix.
