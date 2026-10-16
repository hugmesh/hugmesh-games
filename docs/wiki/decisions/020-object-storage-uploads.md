# Decision 020 — Object-storage-backed game uploads

**Status**: Accepted 2026-05-12.

**Context**: Game-bundle uploads have always landed on the host filesystem under `app.uploads.games-dir`. That works on one host. It also stops the BFF from scaling out: every replica would need a shared filesystem (NFS, EFS, etc.), or the replicas drift apart. The data-architect-overview userguide named this as one of the four cloud-relocation swap-points.

## Decision

Add an `UploadStorage` strategy interface with two implementations:

- **`FilesystemUploadStorage`** — the old behaviour, and the default. Bundle files land under `<gamesDir>/<slug>/<sha-prefix>/`. No infra change.
- **`S3UploadStorage`** — an S3-compatible bucket: AWS S3 in prod, MinIO in dev and CI, or any compatible service (Cloudflare R2, Backblaze B2, etc.).

Pick one with `app.uploads.storage=filesystem|s3` (env: `APP_UPLOADS_STORAGE`).

### Locator scheme

`game_uploads.version_dir` (and `games.current_version_dir`) store an opaque locator that the strategy understands:

- `file:<absolute-path>` — filesystem strategy.
- `s3://<bucket>/<slug>/<sha-prefix>` — S3 strategy.

Rows that pre-date the prefix scheme (raw absolute paths) still resolve through `FilesystemUploadStorage`, so switching needs no row migration.

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

- A shared filesystem no longer blocks horizontal scaling.
- `wasm-fragment` content can move behind a CDN by pointing the CDN at the bucket.
- Backups improve. S3 lifecycle policies and versioning replace the host-disk snapshot loop.
- A third backend (e.g. GCS, Azure Blob) needs no change to `GameUploadService` or `AssetController`.

### Negative

- Two extra containers in dev (`minio` + `minio-init` one-shot bucket bootstrap). `docker-compose.yml` gives them no profile, so a plain `docker compose up -d` starts them even in the default `app.uploads.storage=filesystem` mode, where nothing uses them. (The comment on the `minio` service says they are opt-in. That is wrong.)
- `AssetController` now reads through a `Resource` instead of a `FileSystemResource`. An S3 read loads the whole file into memory as a `ByteArrayResource`. That is acceptable at game-bundle sizes (manifest, SQL and a small `bundle/` tree, capped at 200 MB per zip). The hot path is the `.wasm` file, read once per launch.
- Tests that checked filesystem state directly must go through the strategy interface, or run only in `filesystem` mode.

### Neutral

- Resilience4j circuit breakers around S3 calls were deferred at first and have since landed (see Follow-ups). When a read returns null, the classpath fallback in `AssetController` serves the games shipped in the API image.

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

The S3 recipe does not work as written. The `api` service in `docker-compose.yml` forwards `APP_UPLOADS_GAMES_DIR` and no other `APP_UPLOADS_*` variable, so a shell-level `APP_UPLOADS_STORAGE=s3` never reaches the container. `APP_UPLOADS_S3_ENDPOINT` defaults to empty. With `s3` selected, the BFF refuses to start while `APP_UPLOADS_S3_ACCESS_KEY` or `APP_UPLOADS_S3_SECRET_KEY` is blank. First add the variables to the `api` environment in an overlay. For the compose MinIO, use the endpoint `http://minio:9000` and the keys `MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD`.

## Follow-ups

- ~~Pre-signed-URL reads (move the WASM `.wasm` hot path off the BFF cold path).~~ — done. The hook is `UploadStorage#presignReadUrl(locator, relativePath, ttl)`. `S3UploadStorage` returns a short-lived AWS-SDK-v2 `S3Presigner` URL, capped at 15 minutes. `FilesystemUploadStorage` returns null (no pre-signing). `AssetController.tryPresignRedirect(...)` mints the URL and answers 302 when `app.uploads.s3.presigned-reads=true` AND the strategy returns non-null. Otherwise the streaming path is unchanged. Two `AssetControllerTest` cases pin it, both on the legacy path: a 302 with the flag on, and streaming with the flag off. With the flag on, the browser fetches the WASM straight from the bucket and the BFF stays out of the hot path.
- ~~Migration script `scripts/migrate-uploads-to-s3.sh`~~ — done. A one-shot copy of existing host-filesystem uploads into the bucket, then a `version_dir` column flip. It is a dry run unless you pass `--apply`.
- ~~Resilience4j circuit breaker around S3 calls~~ — done. The `CircuitBreakerUploadStorage` decorator wraps the S3 strategy in the `s3` Resilience4j breaker. While the breaker is OPEN, reads return null (`AssetController` has a classpath fallback) and writes throw `UploadStorageException`. Tune it in `application.yml` under `resilience4j.circuitbreaker.instances.s3`. A five-case unit test pins it.
- AWS S3 + Cloudflare R2 + Backblaze B2 acceptance matrix.
