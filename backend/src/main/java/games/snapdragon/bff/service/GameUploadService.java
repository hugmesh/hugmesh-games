package games.snapdragon.bff.service;

import games.snapdragon.bff.model.GameUpload;
import games.snapdragon.bff.repository.GameUploadRepository;
import games.snapdragon.bff.upload.UploadStorage;
import io.r2dbc.spi.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Validates + installs a Snapdragon bundle zip uploaded via the admin
 * console. Pipeline (PLAN section C, simplified for first cut):
 *
 * <ol>
 *   <li>Stream the multipart file bytes into memory (TODO: spool to disk
 *       for big bundles; 50 MB cap for now).</li>
 *   <li>Walk the zip with zip-slip-safe path checks (every entry must
 *       normalise inside the staging dir).</li>
 *   <li>Read manifest.json; reject if {@code schema_version} isn't 2.</li>
 *   <li>SHA-256 the bundle/ tree → version dir name.</li>
 *   <li>Move staging into {@code <gamesDir>/<slug>/<sha-prefix>}.</li>
 *   <li>Inside ONE R2DBC transaction:
 *       <ol type="a">
 *         <li>insert game_uploads row,</li>
 *         <li>execute register.sql (one statement at a time — Postgres
 *             doesn't pipe multi-statement strings well over R2DBC),</li>
 *         <li>UPDATE games SET current_version_dir = …,</li>
 *         <li>mark prior uploads superseded.</li>
 *       </ol>
 *   </li>
 * </ol>
 *
 * <p>Failure rolls the transaction back and best-effort removes the
 * version dir. Active sessions captured the previous version_dir at
 * launch time so a swap doesn't disturb anyone mid-play.
 */
@Service
public class GameUploadService {

    private static final Logger log = LoggerFactory.getLogger(GameUploadService.class);
    private static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");
    private static final long MAX_BUNDLE_BYTES = 200L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 500;

    /**
     * Staging directory under which every upload's pre-validation files
     * land before being handed to the storage strategy. Stays on the
     * host filesystem regardless of the configured storage backend ---
     * the strategy is responsible for COPYING (S3) or MOVING (filesystem)
     * the staged tree into its final home.
     */
    private final Path stagingRoot;
    private final UploadStorage storage;
    private final GameUploadRepository uploads;
    private final DatabaseClient db;
    private final TransactionalOperator tx;

    public GameUploadService(@Value("${app.uploads.games-dir:/var/snapdragon/games}") String gamesDirCfg,
                             UploadStorage storage,
                             GameUploadRepository uploads,
                             ConnectionFactory connectionFactory,
                             TransactionalOperator tx) {
        // The staging root re-uses gamesDir for the filesystem strategy
        // (no behaviour change), and for the S3 strategy lives in /tmp
        // so we don't require gamesDir to exist on the BFF host at all.
        this.stagingRoot = "s3".equals(storage.label())
                ? Path.of(System.getProperty("java.io.tmpdir"), "snapdragon-uploads")
                : Path.of(gamesDirCfg);
        this.storage = storage;
        this.uploads = uploads;
        this.db = DatabaseClient.create(connectionFactory);
        this.tx = tx;
        log.info("[upload] storage={} stagingRoot={}", storage.label(), stagingRoot);
    }

    public record InstalledUpload(String slug, String contentHash, String versionDir, Instant uploadedAt) {}

    /**
     * Install a single bundle zip. The caller hands over the bytes and
     * the {@code uploadedBy} = JWT subject.
     */
    public Mono<InstalledUpload> install(byte[] zipBytes, String uploadedBy) {
        return install(zipBytes, uploadedBy, slug -> Mono.empty());
    }

    /**
     * Variant that runs a per-slug authorization check after staging
     * exposes the slug from the manifest. The caller is the only one
     * that knows the JWT's authorities + the right service to ask;
     * controller-level {@code @PreAuthorize} can only check the role,
     * not the per-game scope. The publisher upload path threads
     * {@link games.snapdragon.bff.service.PublisherService#requireOwns}
     * through this hook; admin paths pass {@code slug -> Mono.empty()}.
     *
     * <p>The hook fires AFTER the zip has been validated + the manifest
     * parsed (so slug is known) but BEFORE any filesystem move or DB
     * write happens. A failed scope check rolls back the staged dir
     * and surfaces the hook's error to the caller as-is — the
     * publisher path produces a 403.
     */
    public Mono<InstalledUpload> install(byte[] zipBytes,
                                         String uploadedBy,
                                         Function<String, Mono<Void>> requireScope) {
        if (zipBytes == null || zipBytes.length == 0) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "empty upload"));
        }
        if (zipBytes.length > MAX_BUNDLE_BYTES) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST,
                    "bundle exceeds " + MAX_BUNDLE_BYTES + " bytes"));
        }

        return Mono.fromCallable(() -> stageAndValidate(zipBytes))
                .flatMap(staged -> requireScope.apply(staged.slug())
                        .onErrorResume(err -> {
                            // Authorization rejected — clean up the
                            // staging dir before propagating so we
                            // don't leak inodes per refused upload.
                            cleanup(staged.stagingDir());
                            return Mono.error(err);
                        })
                        .then(writeAndRegister(staged, uploadedBy)));
    }

    // ── staging + validation (blocking; runs on Mono.fromCallable) ──────────

    private record StagedBundle(
            String slug,
            String contentHash,
            Path stagingDir,
            String manifestJson,
            String registerSql,
            String displayName,
            String description) {}

    private StagedBundle stageAndValidate(byte[] zipBytes) {
        Path staging;
        try {
            Files.createDirectories(stagingRoot);
            staging = Files.createTempDirectory(stagingRoot, "snapdragon-staging-");
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "could not create staging dir: " + e.getMessage());
        }

        Map<String, byte[]> bundleFiles = new HashMap<>();
        String manifestJson = null;
        String registerSql = null;

        try (ZipInputStream zis = new ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            int count = 0;
            while ((e = zis.getNextEntry()) != null) {
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new ResponseStatusException(BAD_REQUEST, "zip has too many entries");
                }
                String name = e.getName();
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw new ResponseStatusException(BAD_REQUEST, "zip-slip-unsafe entry: " + name);
                }
                if (e.isDirectory()) continue;

                byte[] bytes = readAll(zis);
                if ("manifest.json".equals(name)) {
                    manifestJson = new String(bytes);
                } else if ("register.sql".equals(name)) {
                    registerSql = new String(bytes);
                } else if (name.startsWith("bundle/")) {
                    String rel = name.substring("bundle/".length());
                    if (!rel.isEmpty()) {
                        bundleFiles.put(rel, bytes);
                    }
                }
            }
        } catch (IOException ex) {
            cleanup(staging);
            throw new ResponseStatusException(BAD_REQUEST, "malformed zip: " + ex.getMessage());
        }

        if (manifestJson == null) {
            cleanup(staging);
            throw new ResponseStatusException(BAD_REQUEST, "manifest.json missing from bundle zip");
        }
        if (bundleFiles.isEmpty()) {
            cleanup(staging);
            throw new ResponseStatusException(BAD_REQUEST, "bundle/ tree is empty");
        }

        // Hand-rolled JSON parsing to dodge Jackson reflection at the
        // upload endpoint. We need very few fields: schema_version, slug,
        // game.name, game.description.
        int schemaVersion = parseInt(manifestJson, "\"schema_version\"", 0);
        if (schemaVersion != 2) {
            cleanup(staging);
            throw new ResponseStatusException(BAD_REQUEST,
                    "manifest.schema_version must be 2; got " + schemaVersion);
        }
        String slug = parseStringInObject(manifestJson, "\"game\"", "\"slug\"");
        String displayName = parseStringInObject(manifestJson, "\"game\"", "\"name\"");
        String description = parseStringInObject(manifestJson, "\"game\"", "\"description\"");
        if (slug == null || !SLUG_RE.matcher(slug).matches()) {
            cleanup(staging);
            throw new ResponseStatusException(BAD_REQUEST,
                    "manifest.game.slug missing or invalid: " + slug);
        }

        // Write the bundle files into staging and SHA-256 the tree at
        // the same time (deterministic order so the same content yields
        // the same hash across runs).
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (Exception ex) {
            cleanup(staging);
            throw new RuntimeException(ex);
        }
        List<String> sortedNames = new ArrayList<>(bundleFiles.keySet());
        sortedNames.sort(String::compareTo);
        for (String rel : sortedNames) {
            try {
                Path target = staging.resolve(rel).normalize();
                if (!target.startsWith(staging)) {
                    throw new ResponseStatusException(BAD_REQUEST, "zip-slip blocked: " + rel);
                }
                Files.createDirectories(target.getParent());
                byte[] data = bundleFiles.get(rel);
                Files.write(target, data);
                md.update(rel.getBytes());
                md.update((byte) 0);
                md.update(data);
                md.update((byte) 0);
            } catch (IOException ex) {
                cleanup(staging);
                throw new ResponseStatusException(BAD_REQUEST,
                        "could not write bundle file " + rel + ": " + ex.getMessage());
            }
        }
        String contentHash = toHex(md.digest());

        return new StagedBundle(slug, contentHash, staging, manifestJson, registerSql, displayName, description);
    }

    // ── install (atomic move + transactional DB updates) ─────────────────────

    private Mono<InstalledUpload> writeAndRegister(StagedBundle staged, String uploadedBy) {
        String locator;
        try {
            // Storage strategy claims ownership of stagingDir from here.
            // Filesystem impl atomically moves it under gamesDir/<slug>/<hash>;
            // S3 impl walks + putObject's each file and cleans the dir up.
            // The returned locator is what we persist in game_uploads.version_dir
            // for both AssetController reads + the pruner sweeps.
            locator = storage.installFromStaging(
                    staged.slug(),
                    staged.contentHash().substring(0, 16),
                    staged.stagingDir());
        } catch (RuntimeException ex) {
            cleanup(staged.stagingDir());
            return Mono.error(ex);
        }

        GameUpload row = new GameUpload();
        row.setSlug(staged.slug());
        row.setVersionDir(locator);
        row.setContentHash(staged.contentHash());
        row.setManifestJson(staged.manifestJson());
        row.setUploadedBy(uploadedBy);

        Mono<GameUpload> txn = uploads.save(row)
                .flatMap(saved -> {
                    Mono<Void> sqlStep = staged.registerSql() == null
                            ? Mono.empty()
                            : runRegisterSql(staged.registerSql());
                    Mono<Long> markCurrent = db.sql("UPDATE games SET current_version_dir = :dir WHERE slug = :slug")
                            .bind("dir", locator)
                            .bind("slug", staged.slug())
                            .fetch()
                            .rowsUpdated();
                    Mono<Integer> markPriors = uploads.markPriorsSuperseded(staged.slug(), saved.getId());
                    return sqlStep
                            .then(markCurrent)
                            .then(markPriors)
                            .thenReturn(saved);
                });

        return tx.transactional(txn)
                .map(saved -> new InstalledUpload(saved.getSlug(), saved.getContentHash(),
                                                  saved.getVersionDir(), saved.getUploadedAt()));
    }

    /**
     * Splits a multi-statement register.sql into individual statements
     * and runs them in order. Postgres + R2DBC don't pipe a `;`-separated
     * blob cleanly, but the Godot plugin emits well-formed single-shot
     * upserts so naïve splitting on `;` outside of strings is safe.
     */
    private Mono<Void> runRegisterSql(String sql) {
        List<String> stmts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && (i == 0 || sql.charAt(i - 1) != '\\')) inString = !inString;
            if (c == ';' && !inString) {
                String s = cur.toString().trim();
                if (!s.isEmpty() && !s.startsWith("--")) stmts.add(s);
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty() && !tail.startsWith("--")) stmts.add(tail);

        return Flux.fromIterable(stmts)
                .concatMap(s -> {
                    log.info("[upload] register.sql exec: {}", s.length() > 80 ? s.substring(0, 80) + "…" : s);
                    return db.sql(s).fetch().rowsUpdated().then();
                })
                .then();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static int parseInt(String json, String key, int fallback) {
        int idx = json.indexOf(key);
        if (idx < 0) return fallback;
        idx = json.indexOf(':', idx);
        if (idx < 0) return fallback;
        idx++;
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        int end = idx;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        if (end == idx) return fallback;
        try { return Integer.parseInt(json.substring(idx, end)); }
        catch (NumberFormatException e) { return fallback; }
    }

    /**
     * Naïve nested-string lookup: find the parent key, then within its
     * brace-balanced object find the inner key's string value. Good
     * enough for the small, well-formed manifests the Godot plugin
     * emits. NOT a general JSON parser — don't use elsewhere.
     */
    private static String parseStringInObject(String json, String parentKey, String innerKey) {
        int p = json.indexOf(parentKey);
        if (p < 0) return null;
        int braceStart = json.indexOf('{', p);
        if (braceStart < 0) return null;
        int depth = 0, end = -1;
        for (int i = braceStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i; break; } }
        }
        if (end < 0) return null;
        String slice = json.substring(braceStart, end + 1);
        int k = slice.indexOf(innerKey);
        if (k < 0) return null;
        int colon = slice.indexOf(':', k);
        int q1 = slice.indexOf('"', colon);
        if (q1 < 0) return null;
        int q2 = slice.indexOf('"', q1 + 1);
        if (q2 < 0) return null;
        return slice.substring(q1 + 1, q2);
    }

    private static void cleanup(Path dir) {
        if (dir == null) return;
        try (var s = Files.walk(dir)) {
            s.sorted((a, b) -> b.toString().length() - a.toString().length())
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
