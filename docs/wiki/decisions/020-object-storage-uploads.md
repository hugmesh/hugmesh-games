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

- Two extra containers in dev (`minio` + `minio-init` one-shot bucket bootstrap) — gated behind opt-in (the default `app.uploads.storage=filesystem` keeps vanilla `docker compose up -d` working).
- `AssetController` now goes through a `Resource` rather than a `FileSystemResource` directly — S3 reads bring the whole file into memory via `ByteArrayResource`. Acceptable for game-bundle sizes (manifest+SQL+small bundle/ tree, capped at 200 MB); the hot path is the WASM `.wasm` file which streams once per launch.
- Tests that previously checked filesystem state directly need updating to go through the strategy interface (or to run only against the `filesystem` mode).

### Neutral

- Resilience4j circuit breakers around S3 calls deferred to a follow-up; today an S3 outage cascades as a 5xx on `/api/assets/levels/*`. The classpath fallback in `AssetController` covers the games shipped in the API image.

## Implementation

Files:

- `backend/src/main/java/games/snapdragon/bff/upload/UploadStorage.java` — interface
- `backend/src/main/java/games/snapdragon/bff/upload/FilesystemUploadStorage.java`
- `backend/src/main/java/games/snapdragon/bff/upload/S3UploadStorage.java`
- `backend/src/main/java/games/snapdragon/bff/upload/UploadStorageConfig.java` — bean selection
- `backend/src/main/java/games/snapdragon/bff/service/GameUploadService.java` — strategy call site for writes
- `backend/src/main/java/games/snapdragon/bff/service/GameUploadPruner.java` — strategy call site for deletes
- `backend/src/main/java/games/snapdragon/bff/controller/AssetController.java` — strategy call site for reads
- `backend/pom.xml` — `software.amazon.awssdk:s3` (with `url-connection-client` to avoid clashing with Reactor Netty)
- `docker-compose.yml` — `minio` + `minio-init` services + `minio-data` volume
- `backend/src/main/resources/application.yml` — `app.uploads.storage` + `app.uploads.s3.*` config block

### Verification

```bash
# Filesystem mode (default; no MinIO needed)
docker compose up -d
curl -X POST /api/admin/games/upload -F file=@bundle.zip   # → 200, version_dir = file:/var/snapdragon/games/<slug>/<hash>

# S3 mode
docker compose up -d minio minio-init
APP_UPLOADS_STORAGE=s3 docker compose up -d api
curl -X POST /api/admin/games/upload -F file=@bundle.zip   # → 200, version_dir = s3://snapdragon-uploads/<slug>/<hash>
```

## Follow-ups

- Pre-signed-URL reads (move the WASM `.wasm` hot path off the BFF cold path).
- Migration script `scripts/migrate-uploads-to-s3.sh` (one-shot copy of existing host-filesystem uploads into MinIO).
- Resilience4j circuit breaker around S3 calls (mirror the Cassandra pattern in `RateLimitFilter`).
- AWS S3 + Cloudflare R2 + Backblaze B2 acceptance matrix.
