package games.hugmesh.vnbuilder.service;

import games.hugmesh.vnbuilder.entity.VnAsset;
import games.hugmesh.vnbuilder.entity.VnProject;
import games.hugmesh.vnbuilder.repository.VnAssetRepository;
import games.hugmesh.vnbuilder.util.SlugUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@Service
public class AssetStorageService {

    private static final Set<String> ALLOWED_MIME = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/svg+xml");
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final Path assetsDir;
    private final VnAssetRepository assets;
    private final VnProjectService projects;

    public AssetStorageService(@Value("${app.build.assets-dir}") String dir,
                               VnAssetRepository assets,
                               VnProjectService projects) {
        this.assetsDir = Path.of(dir);
        this.assets = assets;
        this.projects = projects;
    }

    public Mono<VnAsset> upload(UUID pid, String slug, String kind, FilePart fp, String owner) {
        if (!SlugUtil.isValid(slug)) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset slug invalid"));
        if (kind == null || kind.isBlank()) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset kind required"));
        String mime = fp.headers().getContentType() != null ? fp.headers().getContentType().toString() : null;
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported mime type; allowed: " + ALLOWED_MIME));
        }
        return projects.get(pid, owner).flatMap(p -> store(p, slug, kind, mime, fp));
    }

    /** List a project's assets newest-first (ownership-checked). Mirrors the
     *  rpg-builder AssetStorageService.list — the maker UI's asset gallery +
     *  pickers read this. */
    public Flux<VnAsset> list(UUID pid, String owner) {
        return projects.get(pid, owner)
                .flatMapMany(p -> assets.findByProjectIdOrderByUploadedAtDesc(pid));
    }

    /**
     * Persist already-materialized bytes (e.g. procedurally generated SVG)
     * through the SAME storage layout an uploaded asset uses, so downstream
     * materialization + rendering treat generated and uploaded assets
     * identically. Ownership-checked like {@link #upload}.
     */
    public Mono<VnAsset> storeGenerated(UUID pid, String slug, String kind, String mime,
                                        byte[] bytes, String owner) {
        if (!SlugUtil.isValid(slug)) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset slug invalid"));
        if (kind == null || kind.isBlank()) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "asset kind required"));
        if (mime == null || !ALLOWED_MIME.contains(mime)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unsupported mime type; allowed: " + ALLOWED_MIME));
        }
        if (bytes == null || bytes.length == 0) return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty asset bytes"));
        return projects.get(pid, owner).flatMap(p -> storeBytes(p, slug, kind, mime, bytes));
    }

    private Mono<VnAsset> storeBytes(VnProject p, String slug, String kind, String mime, byte[] bytes) {
        UUID assetId = UUID.randomUUID();
        Path projDir = assetsDir.resolve(p.getId().toString());
        Path target = projDir.resolve(assetId.toString());
        return Mono.fromCallable(() -> {
                    if (bytes.length > MAX_BYTES) {
                        throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                "asset exceeds " + MAX_BYTES + " bytes");
                    }
                    Files.createDirectories(projDir);
                    Files.write(target, bytes);
                    VnAsset a = new VnAsset();
                    a.setId(assetId); a.setProjectId(p.getId()); a.setSlug(slug); a.setKind(kind);
                    a.setMimeType(mime); a.setByteSize(bytes.length); a.setStoragePath(target.toString());
                    a.setUploadedBy(p.getOwnerCasdoorId()); a.setUploadedAt(OffsetDateTime.now());
                    return a;
                }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(assets::save);
    }

    private Mono<VnAsset> store(VnProject p, String slug, String kind, String mime, FilePart fp) {
        UUID assetId = UUID.randomUUID();
        Path projDir = assetsDir.resolve(p.getId().toString());
        Path target = projDir.resolve(assetId.toString());
        return Mono.fromCallable(() -> { Files.createDirectories(projDir); return target; })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(t -> fp.transferTo(t).thenReturn(t))
                .flatMap(t -> Mono.fromCallable(() -> {
                            long size = Files.size(t);
                            if (size > MAX_BYTES) {
                                Files.deleteIfExists(t);
                                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                                        "asset exceeds " + MAX_BYTES + " bytes");
                            }
                            VnAsset a = new VnAsset();
                            a.setId(assetId); a.setProjectId(p.getId()); a.setSlug(slug); a.setKind(kind);
                            a.setMimeType(mime); a.setByteSize(size); a.setStoragePath(t.toString());
                            a.setUploadedBy(p.getOwnerCasdoorId()); a.setUploadedAt(OffsetDateTime.now());
                            return a;
                        }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(assets::save);
    }

    public Mono<byte[]> read(UUID pid, UUID assetId, String owner) {
        return projects.get(pid, owner)
                .flatMap(p -> assets.findById(assetId))
                // IDOR guard: the project ownership check above only proves the
                // caller owns `pid`. An asset id from another project must NOT
                // be readable through this project's path, so verify the row is
                // actually scoped to `pid` and 404 otherwise (mirrors the
                // pid-ownership check projects.get already enforces).
                // IDOR guard: the project ownership check above only proves the
                // caller owns `pid`. An asset id from another project must NOT
                // be readable through this project's path, so verify the row is
                // actually scoped to `pid` and 404 otherwise (mirrors the
                // pid-ownership check projects.get already enforces).
                .flatMap(a -> {
                    if (!pid.equals(a.getProjectId())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "asset not found in project"));
                    }
                    return Mono.fromCallable(() -> Files.readAllBytes(Path.of(a.getStoragePath())))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                .onErrorMap(IOException.class, e -> new ResponseStatusException(HttpStatus.NOT_FOUND, "asset bytes missing on disk"));
    }
}
