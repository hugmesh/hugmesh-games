package games.snapdragon.bff.service;

import games.snapdragon.bff.model.GameUpload;
import games.snapdragon.bff.repository.GameUploadRepository;
import games.snapdragon.bff.security.DomainRegistry;
import games.snapdragon.bff.upload.RegisterSqlPolicy;
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

    /**
     * Least-privilege Postgres role the attacker-controlled register.sql is run
     * under (NOSUPERUSER, no DDL, INSERT/UPDATE/SELECT on only the six catalog
     * tables — created by migration 054). Applied via a transaction-scoped
     * {@code SET LOCAL ROLE} so the install stays atomic on one connection and
     * the role reverts on commit/rollback. Blank disables the switch (the
     * application-layer allowlist + hardened lexer still apply); validated only
     * as a SQL identifier to keep it out of the injection surface.
     */
    private final String registerSqlRole;
    private static final Pattern ROLE_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    public GameUploadService(@Value("${app.uploads.games-dir:/var/snapdragon/games}") String gamesDirCfg,
                             @Value("${app.uploads.register-sql.role:snapdragon_register_sql}") String registerSqlRole,
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
        String role = registerSqlRole == null ? "" : registerSqlRole.trim();
        if (!role.isEmpty() && !ROLE_RE.matcher(role).matches()) {
            throw new IllegalArgumentException(
                    "app.uploads.register-sql.role must be a plain SQL identifier: " + role);
        }
        this.registerSqlRole = role;
        log.info("[upload] storage={} stagingRoot={} registerSqlRole={}",
                storage.label(), stagingRoot, role.isEmpty() ? "(disabled)" : role);
    }

    public record InstalledUpload(String slug, String contentHash, String versionDir, Instant uploadedAt) {}

    /**
     * Install a single bundle zip. The caller hands over the bytes and
     * the {@code uploadedBy} = JWT subject. Domain defaults to
     * {@code "default"} (global / single-tenant) — the multi-brand path
     * uses the {@code domain}-carrying overload below.
     */
    public Mono<InstalledUpload> install(byte[] zipBytes, String uploadedBy) {
        return install(zipBytes, uploadedBy, DomainRegistry.DEFAULT_DOMAIN, slug -> Mono.empty());
    }

    /**
     * Back-compat overload (no explicit domain) — stamps the installed game with the
     * {@code "default"} (global) brand. Existing callers that don't yet thread a
     * brand keep working unchanged.
     */
    public Mono<InstalledUpload> install(byte[] zipBytes,
                                         String uploadedBy,
                                         Function<String, Mono<Void>> requireScope) {
        return install(zipBytes, uploadedBy, DomainRegistry.DEFAULT_DOMAIN, requireScope);
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
     *
     * <p>{@code domain} is the trusted brand stamped onto the installed
     * {@code games} row (migration 043) — the uploader's issuer-derived
     * domain ({@code Roles.domainOf(jwt, registry)}), NOT anything from the
     * uploaded {@code register.sql}. {@code "default"} (global) on the
     * single-tenant cluster. The stamp is a server-controlled {@code UPDATE}
     * inside the same transaction so a brand-X upload can never claim a
     * brand-Y scope by hand-crafting its register.sql.
     */
    public Mono<InstalledUpload> install(byte[] zipBytes,
                                         String uploadedBy,
                                         String domain,
                                         Function<String, Mono<Void>> requireScope) {
        if (zipBytes == null || zipBytes.length == 0) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "empty upload"));
        }
        if (zipBytes.length > MAX_BUNDLE_BYTES) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST,
                    "bundle exceeds " + MAX_BUNDLE_BYTES + " bytes"));
        }

        String stampDomain = (domain == null || domain.isBlank())
                ? DomainRegistry.DEFAULT_DOMAIN
                : domain.trim();

        return Mono.fromCallable(() -> stageAndValidate(zipBytes))
                .flatMap(staged -> requireScope.apply(staged.slug())
                        .onErrorResume(err -> {
                            // Authorization rejected — clean up the
                            // staging dir before propagating so we
                            // don't leak inodes per refused upload.
                            cleanup(staged.stagingDir());
                            return Mono.error(err);
                        })
                        .then(writeAndRegister(staged, uploadedBy, stampDomain)));
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

    // TODO reactive: review this catch for onErrorResume — storage.installFromStaging
    // is a blocking call; wrapping writeAndRegister in Mono.fromCallable (then chaining
    // the DB steps via flatMap) would let onErrorResume replace the boundary try/catch.
    private Mono<InstalledUpload> writeAndRegister(StagedBundle staged, String uploadedBy, String domain) {
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
                    // FIRST-WRITER-WINS brand stamp: capture whether the games row
                    // already exists BEFORE register.sql creates/upserts it. A brand is
                    // assigned only when the game is first CREATED; an ordinary re-upload
                    // PRESERVES the existing domain. This stops a brand-X uploader from
                    // "capturing" a 'default'/global title (or clobbering a foreign
                    // brand's stamp) by re-uploading it — moving a game's domain is
                    // reserved for a future superadmin operation, not a side effect of
                    // upload. Runs first, inside the same transaction (consistent read).
                    Mono<Boolean> gameExisted = db.sql("SELECT 1 FROM games WHERE slug = :slug")
                            .bind("slug", staged.slug())
                            .fetch()
                            .first()
                            .hasElement();
                    return gameExisted.flatMap(existed -> {
                        Mono<Void> sqlStep = staged.registerSql() == null
                                ? Mono.empty()
                                : runRegisterSql(staged.registerSql());
                        Mono<Long> markCurrent = db.sql("UPDATE games SET current_version_dir = :dir WHERE slug = :slug")
                                .bind("dir", locator)
                                .bind("slug", staged.slug())
                                .fetch()
                                .rowsUpdated();
                        // Stamp the TRUSTED brand onto the games row — server-controlled,
                        // keyed by slug, AFTER sqlStep so the row exists. Skipped on a
                        // re-upload (existed) so the domain is never moved by an upload;
                        // a hand-crafted register.sql can't set a foreign domain either,
                        // since this overwrites whatever it wrote for a new game. 'default'
                        // on the single-tenant cluster (no-op vs the column default).
                        Mono<Long> stampDomain = existed
                                ? Mono.just(0L)
                                : db.sql("UPDATE games SET domain = :domain WHERE slug = :slug")
                                        .bind("domain", domain)
                                        .bind("slug", staged.slug())
                                        .fetch()
                                        .rowsUpdated();
                        Mono<Integer> markPriors = uploads.markPriorsSuperseded(staged.slug(), saved.getId());
                        return sqlStep
                                .then(markCurrent)
                                .then(stampDomain)
                                .then(markPriors)
                                .thenReturn(saved);
                    });
                });

        return tx.transactional(txn)
                .map(saved -> new InstalledUpload(saved.getSlug(), saved.getContentHash(),
                                                  saved.getVersionDir(), saved.getUploadedAt()));
    }

    /**
     * Splits a multi-statement register.sql into individual statements and runs
     * them in order, under the least-privilege role, inside the caller's
     * transaction.
     *
     * <p><b>Defence in depth (OWASP A03/A04).</b> register.sql is
     * attacker-controlled bundle content; three independent layers contain it:
     * <ol>
     *   <li>{@link RegisterSqlPolicy#vet} — the default-deny application
     *       allowlist (only INSERTs into the six catalog tables / a games
     *       feature-flag UPDATE), now backed by a standard-conforming-strings
     *       lexer that rejects backslash/E''/$tag$/block-comment desyncs;</li>
     *   <li>per-statement execution — each VETTED statement is sent on its own
     *       to {@code db.sql(...)}, and a belt-and-suspenders guard rejects any
     *       vetted statement that still contains a top-level {@code ;}, so a
     *       multi-statement blob can never reach Postgres' simple-query protocol
     *       (which would otherwise run every {@code ;}-separated statement);</li>
     *   <li>least-privilege role — {@code SET LOCAL ROLE} switches the session to
     *       a NOSUPERUSER, no-DDL role scoped to only the six catalog tables for
     *       the duration of register.sql, then {@code RESET ROLE} restores the
     *       app role. {@code LOCAL} ties the switch to THIS transaction, so the
     *       install stays atomic and the role reverts on commit/rollback even on
     *       error. Skipped (with the other two layers still active) when
     *       {@code app.uploads.register-sql.role} is blank.</li>
     * </ol>
     * A statement that violates the allowlist fails the whole upload with 400
     * and rolls the install transaction back.
     */
    private Mono<Void> runRegisterSql(String sql) {
        return Mono.fromCallable(() -> RegisterSqlPolicy.vet(sql))
                .flatMapMany(Flux::fromIterable)
                .concatMap(s -> {
                    // Belt-and-suspenders: a vetted statement must be a SINGLE
                    // statement. If splitting ever desynced again, this stops a
                    // multi-statement string from reaching the simple-query
                    // protocol. (vet() already rejects the known desyncs.)
                    if (containsTopLevelSeparator(s)) {
                        return Mono.error(new ResponseStatusException(BAD_REQUEST,
                                "register.sql statement contains an embedded ';' separator"));
                    }
                    log.info("[upload] register.sql exec: {}", s.length() > 80 ? s.substring(0, 80) + "…" : s);
                    return db.sql(s).fetch().rowsUpdated().then();
                })
                .then()
                .as(this::underLeastPrivRole);
    }

    /**
     * Wraps the register.sql execution in a transaction-scoped role switch to
     * the least-privilege {@code registerSqlRole}. {@code SET LOCAL ROLE} +
     * {@code RESET ROLE} both run on the same pooled connection inside the
     * active transaction. No-op when the role is disabled (blank).
     */
    private Mono<Void> underLeastPrivRole(Mono<Void> body) {
        if (registerSqlRole.isEmpty()) {
            return body;
        }
        // registerSqlRole is validated as a SQL identifier in the constructor,
        // so this interpolation is safe (no bind params for SET ROLE in PG).
        Mono<Void> setRole = db.sql("SET LOCAL ROLE \"" + registerSqlRole + "\"")
                .fetch().rowsUpdated().then();
        Mono<Void> resetRole = db.sql("RESET ROLE").fetch().rowsUpdated().then();
        return setRole
                .then(body)
                // RESET on success AND on error; SET LOCAL would also revert at
                // tx end, but resetting eagerly keeps the rest of the tx (the
                // domain stamp / markCurrent) running as the app role.
                .onErrorResume(err -> resetRole.onErrorComplete().then(Mono.error(err)))
                .then(resetRole);
    }

    /** True if {@code s} has a {@code ;} outside a (standard-conforming) string literal. */
    private static boolean containsTopLevelSeparator(String s) {
        boolean inString = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
            } else if (c == ';') {
                return true;
            }
        }
        return false;
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
