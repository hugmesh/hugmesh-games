package games.hugmesh.bff.service;

import games.hugmesh.bff.model.GameUpload;
import games.hugmesh.bff.repository.GameUploadRepository;
import games.hugmesh.bff.security.DomainRegistry;
import games.hugmesh.bff.upload.RegisterSqlPolicy;
import games.hugmesh.bff.upload.UploadStorage;
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
import reactor.core.scheduler.Schedulers;

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
 * Validates and installs HugMesh game-bundle ZIP archives uploaded via the
 * admin console or the publisher upload endpoint.
 *
 * <h2>Responsibility</h2>
 *
 * <p>Handles the full lifecycle of a multipart game-bundle upload from
 * raw bytes to an installed, database-registered, domain-stamped game version:
 *
 * <ol>
 *   <li><b>Receive</b> — the caller provides raw ZIP bytes (max
 *       {@value #MAX_BUNDLE_BYTES} bytes, {@value #MAX_ZIP_ENTRIES} entries).
 *       A 50 MB soft cap is enforced here; controllers impose stricter limits
 *       via Spring's {@code spring.codec.max-in-memory-size} / multipart config.</li>
 *   <li><b>Stage + validate</b> ({@link #stageAndValidate}) — the ZIP is
 *       unpacked into a temporary staging directory under {@code stagingRoot}.
 *       Every entry is zip-slip-checked (no {@code ..} / absolute / backslash
 *       paths). {@code manifest.json} must be present and have
 *       {@code schema_version == 2}; {@code bundle/} must be non-empty.
 *       The bundle tree is written to disk and SHA-256'd in a single
 *       deterministic pass so the same content always produces the same hash.</li>
 *   <li><b>Authorize</b> — a caller-supplied {@code requireScope} hook runs
 *       AFTER the slug is known from {@code manifest.json} but BEFORE any
 *       filesystem move or DB write. The publisher path threads
 *       {@link PublisherService#requireOwns} through this hook; admin paths
 *       pass a no-op.</li>
 *   <li><b>Install + register</b> ({@link #writeAndRegister}) — the storage
 *       strategy atomically moves (filesystem) or uploads (S3) the staged tree
 *       into its final location. Inside a single R2DBC transaction:
 *       <ol type="a">
 *         <li>a {@code game_uploads} row is inserted;</li>
 *         <li>{@code register.sql} statements are executed one at a time under
 *             the least-privilege Postgres role (see {@link #runRegisterSql});</li>
 *         <li>{@code games.current_version_dir} is updated to the new locator;</li>
 *         <li>the brand domain is stamped onto the {@code games} row (first-writer-wins
 *             — a re-upload of an existing game preserves the existing domain);</li>
 *         <li>prior upload rows for the same slug are marked superseded.</li>
 *       </ol>
 *   </li>
 * </ol>
 *
 * <p><b>Rollback behaviour</b>: a transaction failure rolls back all DB changes.
 * The service then best-effort removes the version directory from storage so no
 * orphaned artifacts are left behind. Active sessions that captured the previous
 * {@code version_dir} at launch time are unaffected — the swap takes effect for
 * new launches only.
 *
 * <h2>Defence in depth on {@code register.sql}</h2>
 *
 * <p>{@code register.sql} is attacker-controlled bundle content. Three
 * independent layers contain it (see {@link #runRegisterSql} for detail):
 * <ol>
 *   <li>{@link RegisterSqlPolicy#vet} allowlist — default-deny, only permits
 *       INSERTs into the six catalog tables and a games feature-flag UPDATE;</li>
 *   <li>per-statement execution with a top-level {@code ;} guard so a
 *       multi-statement blob can never reach the Postgres simple-query protocol;</li>
 *   <li>least-privilege role switch ({@code SET LOCAL ROLE}) scoped to the
 *       install transaction — the role reverts on commit/rollback.</li>
 * </ol>
 *
 * <h2>Storage strategy</h2>
 *
 * <p>The {@link UploadStorage} strategy is resolved at construction time from
 * the configured label:
 * <ul>
 *   <li>{@code filesystem} (default) — moves the staged directory under
 *       {@code <gamesDir>/<slug>/<sha16>}; {@code stagingRoot = gamesDir}.</li>
 *   <li>{@code s3} — walks the staged directory and issues {@code PutObject}
 *       calls; {@code stagingRoot = java.io.tmpdir/hugmesh-uploads} so the
 *       BFF host does not need {@code gamesDir} mounted.</li>
 * </ul>
 *
 * <h2>Multi-tenancy / brand domain stamping</h2>
 *
 * <p>The {@code domain} parameter is the uploader's issuer-derived brand
 * ({@code Roles.domainOf(jwt, registry)}), NOT anything from the uploaded
 * {@code register.sql}. It is server-controlled and written by a trusted
 * {@code UPDATE} inside the install transaction. First-writer-wins: a
 * re-upload of an existing game preserves the domain already on the
 * {@code games} row — an uploader cannot move a game between brands by
 * re-uploading it.
 *
 * <h2>Caller-role access matrix</h2>
 *
 * <pre>
 *   Player      — no access (upload is admin / publisher only)
 *   Publisher   — may upload bundles for games they own via {@code publisher_game_scopes};
 *                 {@code requireScope} hook enforces this at the slug level
 *   Mod         — no access
 *   Admin       — may upload any bundle (passes a no-op {@code requireScope} hook)
 *   Superadmin  — same as Admin
 * </pre>
 *
 * @see RegisterSqlPolicy
 * @see UploadStorage
 * @see PublisherService#requireOwns
 * @see GameUploadPruner
 */
@Service
public class GameUploadService {

    private static final Logger log = LoggerFactory.getLogger(GameUploadService.class);

    /** Slug validation: lowercase alphanumeric + hyphens, 1–64 characters, no leading hyphen. */
    private static final Pattern SLUG_RE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /** Maximum accepted ZIP size in bytes (200 MB). Controllers may enforce stricter limits. */
    private static final long MAX_BUNDLE_BYTES = 200L * 1024 * 1024;

    /** Maximum number of ZIP entries to unpack; guards against ZIP-bomb attacks. */
    private static final int MAX_ZIP_ENTRIES = 500;

    /**
     * Staging directory under which every upload's pre-validation files land
     * before being handed to the storage strategy. Stays on the host filesystem
     * regardless of the configured storage backend — the strategy is responsible
     * for COPYING (S3) or MOVING (filesystem) the staged tree into its final home.
     */
    private final Path stagingRoot;
    private final UploadStorage storage;
    private final GameUploadRepository uploads;
    private final DatabaseClient db;
    private final TransactionalOperator tx;

    /**
     * Least-privilege Postgres role under which the attacker-controlled
     * {@code register.sql} statements are executed (NOSUPERUSER, no DDL,
     * INSERT/UPDATE/SELECT on only the six catalog tables — created by
     * migration 054). Applied via a transaction-scoped {@code SET LOCAL ROLE}
     * so the install stays atomic on one connection and the role reverts on
     * commit/rollback. Blank disables the switch (the application-layer allowlist
     * + hardened lexer still apply); validated as a SQL identifier in the
     * constructor to prevent injection through the config value itself.
     */
    private final String registerSqlRole;

    /** Validates {@code registerSqlRole} is a plain SQL identifier. */
    private static final Pattern ROLE_RE = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,62}$");

    public GameUploadService(@Value("${app.uploads.games-dir:/var/hugmesh/games}") String gamesDirCfg,
                             @Value("${app.uploads.register-sql.role:hugmesh_register_sql}") String registerSqlRole,
                             UploadStorage storage,
                             GameUploadRepository uploads,
                             ConnectionFactory connectionFactory,
                             TransactionalOperator tx) {
        // The staging root re-uses gamesDir for the filesystem strategy
        // (no behaviour change), and for the S3 strategy lives in /tmp
        // so we don't require gamesDir to exist on the BFF host at all.
        this.stagingRoot = "s3".equals(storage.label())
                ? Path.of(System.getProperty("java.io.tmpdir"), "hugmesh-uploads")
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

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Result type emitted by all {@code install} overloads on success.
     *
     * @param slug        the game slug parsed from {@code manifest.json}
     * @param contentHash SHA-256 hex of the installed {@code bundle/} tree
     * @param versionDir  storage locator written to {@code game_uploads.version_dir}
     *                    and {@code games.current_version_dir}
     * @param uploadedAt  server timestamp set by the DB on row insertion
     */
    public record InstalledUpload(String slug, String contentHash, String versionDir, Instant uploadedAt) {}

    /**
     * Convenience overload: installs a bundle ZIP stamped with the global
     * {@code "default"} brand domain. Intended for single-tenant deployments
     * or test callers that do not need to thread a brand.
     *
     * @param zipBytes   raw bytes of the ZIP archive; must not be null or empty
     * @param uploadedBy Casdoor subject of the uploader (from the validated JWT)
     * @return a {@link Mono} emitting the {@link InstalledUpload} descriptor on success
     */
    public Mono<InstalledUpload> install(byte[] zipBytes, String uploadedBy) {
        return install(zipBytes, uploadedBy, DomainRegistry.DEFAULT_DOMAIN, slug -> Mono.empty());
    }

    /**
     * Back-compat overload (no explicit domain) — stamps the installed game with the
     * {@code "default"} (global) brand. Existing callers that don't yet thread a
     * brand keep working unchanged.
     *
     * @param zipBytes     raw bytes of the ZIP archive
     * @param uploadedBy   Casdoor subject of the uploader
     * @param requireScope per-slug authorization hook; publisher path supplies
     *                     {@link PublisherService#requireOwns}, admin path passes
     *                     {@code slug -> Mono.empty()}
     * @return a {@link Mono} emitting the {@link InstalledUpload} descriptor on success
     */
    public Mono<InstalledUpload> install(byte[] zipBytes,
                                         String uploadedBy,
                                         Function<String, Mono<Void>> requireScope) {
        return install(zipBytes, uploadedBy, DomainRegistry.DEFAULT_DOMAIN, requireScope);
    }

    /**
     * Full install pipeline: validate the ZIP, run the per-slug authorization
     * check, move the staged tree to storage, and register the bundle in the DB.
     *
     * <p>The {@code requireScope} hook fires AFTER the ZIP has been validated and
     * the slug is known from {@code manifest.json}, but BEFORE any filesystem move
     * or DB write. A failed scope check cleans up the staging directory and
     * propagates the hook's error as-is (typically a 403 from
     * {@link PublisherService#requireOwns}).
     *
     * <p>{@code domain} is the trusted brand stamp derived server-side from the
     * caller's JWT issuer ({@code Roles.domainOf(jwt, registry)}). It is written
     * inside the install transaction and cannot be influenced by anything in the
     * uploaded ZIP.
     *
     * @param zipBytes     raw bytes of the ZIP archive; rejected if null/empty or
     *                     larger than {@value #MAX_BUNDLE_BYTES} bytes
     * @param uploadedBy   Casdoor subject of the uploader (from the validated JWT)
     * @param domain       brand domain to stamp onto the {@code games} row on first
     *                     creation; {@code "default"} for single-tenant deployments
     * @param requireScope per-slug authorization hook; admin paths pass
     *                     {@code slug -> Mono.empty()}; publisher paths supply
     *                     {@link PublisherService#requireOwns}
     * @return a {@link Mono} emitting an {@link InstalledUpload} on success, or
     *         erroring with a {@link org.springframework.web.server.ResponseStatusException}
     *         (400 for validation failures, 403 for scope refusals)
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

        // Fall back to the global default when the caller passes null/blank domain.
        String stampDomain = (domain == null || domain.isBlank())
                ? DomainRegistry.DEFAULT_DOMAIN
                : domain.trim();

        return Mono.fromCallable(() -> stageAndValidate(zipBytes))
                // stageAndValidate is blocking I/O (unzip + file writes + SHA-256 over
                // the whole bundle — seconds for a real multi-MB zip). subscribeOn moves
                // it off the Netty event-loop thread onto the blocking-work pool.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(staged -> requireScope.apply(staged.slug())
                        .onErrorResume(err -> {
                            // Authorization rejected — clean up the staging dir before
                            // propagating so we don't leak inodes per refused upload.
                            cleanup(staged.stagingDir());
                            return Mono.error(err);
                        })
                        .then(writeAndRegister(staged, uploadedBy, stampDomain)));
    }

    // -------------------------------------------------------------------------
    // Stage + validate (blocking; runs inside Mono.fromCallable)
    // -------------------------------------------------------------------------

    /**
     * Holds the validated, staged bundle content prior to the atomic storage move
     * and DB transaction. All fields are extracted from the ZIP in a single pass
     * so the install step never re-reads the archive.
     */
    private record StagedBundle(
            String slug,
            String contentHash,
            Path stagingDir,
            String manifestJson,
            String registerSql,
            String displayName,
            String description) {}

    /**
     * Extracts, validates, and stages a bundle ZIP into a temporary directory
     * under {@link #stagingRoot}. Performs the following checks in order:
     * <ol>
     *   <li>Creates the staging directory under {@code stagingRoot}.</li>
     *   <li>Unzips the archive entry by entry, zip-slip-checking each path
     *       (rejects entries containing {@code ..}, starting with {@code /},
     *       or containing {@code \}).</li>
     *   <li>Rejects archives with more than {@value #MAX_ZIP_ENTRIES} entries.</li>
     *   <li>Extracts {@code manifest.json} and {@code register.sql} (optional)
     *       verbatim; writes {@code bundle/*} entries to disk.</li>
     *   <li>Rejects if {@code manifest.json} is absent or if the {@code bundle/}
     *       tree is empty.</li>
     *   <li>Validates {@code manifest.schema_version == 2} and that
     *       {@code manifest.game.slug} matches {@link #SLUG_RE}.</li>
     *   <li>SHA-256s the {@code bundle/} tree in sorted-name order for a
     *       deterministic content hash.</li>
     * </ol>
     *
     * <p>Any validation failure cleans up the staging directory and throws a
     * 400 {@link ResponseStatusException}.
     *
     * <p><b>Threading</b>: this method performs blocking I/O (ZIP decompression,
     * file writes, SHA-256 hashing). It must only be called inside a
     * {@link Mono#fromCallable} subscribed on a boundedElastic scheduler.
     *
     * @param zipBytes the raw bytes of the ZIP archive
     * @return a populated {@link StagedBundle} record ready for the install step
     * @throws ResponseStatusException (400) if validation fails at any step
     */
    private StagedBundle stageAndValidate(byte[] zipBytes) {
        Path staging;
        try {
            Files.createDirectories(stagingRoot);
            staging = Files.createTempDirectory(stagingRoot, "hugmesh-staging-");
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
                // Guard against ZIP bombs: cap total entry count.
                if (++count > MAX_ZIP_ENTRIES) {
                    throw new ResponseStatusException(BAD_REQUEST, "zip has too many entries");
                }
                String name = e.getName();
                // Zip-slip guard: reject any entry that tries to escape the staging root.
                if (name.contains("..") || name.startsWith("/") || name.contains("\\")) {
                    throw new ResponseStatusException(BAD_REQUEST, "zip-slip-unsafe entry: " + name);
                }
                if (e.isDirectory()) continue;

                byte[] bytes = readAll(zis);
                // Route the three recognised entry types; ignore everything else.
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
                // Second zip-slip check after normalize() — belt-and-suspenders.
                if (!target.startsWith(staging)) {
                    throw new ResponseStatusException(BAD_REQUEST, "zip-slip blocked: " + rel);
                }
                Files.createDirectories(target.getParent());
                byte[] data = bundleFiles.get(rel);
                Files.write(target, data);
                // Hash the filename and content together so a rename changes the hash.
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

    // -------------------------------------------------------------------------
    // Install: atomic storage move + transactional DB updates
    // -------------------------------------------------------------------------

    /**
     * Hands the staged bundle to the storage strategy and registers the
     * installation inside a single R2DBC transaction.
     *
     * <p>Reactive marble diagram:
     * <pre>
     *   storage.installFromStaging(slug, sha16, stagingDir)   ← blocking; returns locator
     *     → uploads.save(row)                                 ← insert game_uploads row
     *       → db.sql("SELECT 1 FROM games …").hasElement()    ← check if game already exists
     *         → runRegisterSql(sql)          (if present)     ← execute register.sql statements
     *         → db.sql("UPDATE games SET current_version_dir…") ← point to new version
     *         → db.sql("UPDATE games SET domain …")
     *             (only on NEW game — first-writer-wins brand stamp)
     *         → uploads.markPriorsSuperseded(slug, id)        ← deprecate old uploads
     *     → tx.transactional(txn)                             ← commit / rollback atomically
     *     → map(saved → InstalledUpload)
     * </pre>
     *
     * <p>If the storage move fails or the transaction rolls back, the service
     * best-effort removes the version directory from storage so no orphaned
     * artifacts are left.
     *
     * <p>The blocking {@code storage.installFromStaging} call is wrapped in
     * {@link Mono#fromCallable} rather than invoked as a plain synchronous
     * method body: this method's result is passed as a plain Java argument
     * to {@code .then(...)} in {@link #install}, so a synchronous call here
     * would run the storage move as a side effect of assembling that
     * argument — BEFORE the {@code requireScope} authorization Mono earlier
     * in the {@code .then()} chain is even subscribed, let alone completes.
     * Deferring via {@code Mono.fromCallable} keeps the move from running
     * until this Mono is subscribed in sequence, i.e. only after
     * authorization has actually succeeded.
     *
     * @param staged     the validated staged bundle from {@link #stageAndValidate}
     * @param uploadedBy Casdoor subject of the uploader
     * @param domain     trusted brand domain to stamp on a new game row
     * @return a {@link Mono} emitting the {@link InstalledUpload} on success
     */
    private Mono<InstalledUpload> writeAndRegister(StagedBundle staged, String uploadedBy, String domain) {
        return Mono.fromCallable(() ->
                        // Storage strategy claims ownership of stagingDir from here.
                        // Filesystem impl atomically moves it under gamesDir/<slug>/<hash>;
                        // S3 impl walks + putObject's each file and cleans the dir up.
                        // The returned locator is what we persist in game_uploads.version_dir
                        // for both AssetController reads + the pruner sweeps.
                        storage.installFromStaging(
                                staged.slug(),
                                staged.contentHash().substring(0, 16),
                                staged.stagingDir()))
                // Mirrors the original catch (RuntimeException): a peek, not a
                // recovery — the same exception continues to propagate after the
                // staging dir is cleaned up. Non-RuntimeException Throwables (e.g.
                // an Error) are intentionally left unhandled, same as before.
                .doOnError(RuntimeException.class, ex -> cleanup(staged.stagingDir()))
                .flatMap(locator -> writeAndRegisterAt(staged, uploadedBy, domain, locator));
    }

    /**
     * Continuation of {@link #writeAndRegister} once the storage move has
     * produced {@code locator}: builds the {@code game_uploads} row and runs
     * the transactional DB chain described in the marble diagram above.
     *
     * @param staged     the validated staged bundle from {@link #stageAndValidate}
     * @param uploadedBy Casdoor subject of the uploader
     * @param domain     trusted brand domain to stamp on a new game row
     * @param locator    the storage locator returned by {@code storage.installFromStaging}
     * @return a {@link Mono} emitting the {@link InstalledUpload} on success
     */
    private Mono<InstalledUpload> writeAndRegisterAt(StagedBundle staged, String uploadedBy, String domain, String locator) {
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
                        // Run register.sql if the bundle included one; skip if absent.
                        Mono<Void> sqlStep = staged.registerSql() == null
                                ? Mono.empty()
                                : runRegisterSql(staged.registerSql());
                        // Point the game catalog to the newly-installed version directory.
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
                        // Mark every earlier upload row for this slug as superseded by the
                        // new upload so the admin console and pruner see the version ordering.
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

    // -------------------------------------------------------------------------
    // register.sql execution
    // -------------------------------------------------------------------------

    /**
     * Splits a multi-statement {@code register.sql} into individual statements
     * and runs them in order inside the caller's R2DBC transaction.
     *
     * <p><b>Defence in depth (OWASP A03/A04)</b> — three independent layers:
     * <ol>
     *   <li>{@link RegisterSqlPolicy#vet} default-deny allowlist — only INSERTs
     *       into the six catalog tables / a games feature-flag UPDATE; backed by
     *       a standard-conforming-strings lexer that strips {@code --} comments
     *       and rejects backslash/E''/$tag$/block-comment/double-quoted-identifier
     *       desyncs, so the executed text is exactly the vetted, comment-free text.</li>
     *   <li>Per-statement execution — each vetted statement runs via its own
     *       {@code db.sql(...)} call; a belt-and-suspenders guard rejects any
     *       statement that still contains a top-level {@code ;}, preventing a
     *       multi-statement blob from reaching Postgres' simple-query protocol.</li>
     *   <li>Least-privilege role — {@code SET LOCAL ROLE} switches the session to
     *       a NOSUPERUSER, no-DDL role for the duration of register.sql, then
     *       {@code RESET ROLE} restores the app role. {@code LOCAL} ties the switch
     *       to the active transaction so the role reverts on commit/rollback.
     *       Skipped when {@code app.uploads.register-sql.role} is blank.</li>
     * </ol>
     *
     * @param sql the raw content of {@code register.sql} from the bundle ZIP
     * @return a {@link Mono} completing empty when all statements succeed, or
     *         erroring with a 400 if the allowlist rejects any statement
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
     * Wraps the register.sql execution body in a transaction-scoped role switch to
     * the least-privilege {@link #registerSqlRole}. Both {@code SET LOCAL ROLE} and
     * {@code RESET ROLE} run on the same pooled connection inside the active
     * transaction. {@code RESET ROLE} is called on both success and error paths
     * so the application role is always restored before the remainder of the
     * transaction (domain stamp, markCurrent) runs.
     *
     * <p>No-op when {@link #registerSqlRole} is blank — the other two defence
     * layers (allowlist + per-statement guard) remain active regardless.
     *
     * @param body the reactive chain to execute under the restricted role
     * @return a {@link Mono} wrapping {@code body} with the role switch, or
     *         {@code body} itself when the role is disabled
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

    /**
     * Belt-and-suspenders guard after {@link RegisterSqlPolicy#vet}: returns
     * {@code true} unless {@code s} is provably ONE statement, so a multi-statement
     * blob never reaches the Postgres simple-query protocol, which would run every
     * {@code ;}-separated statement in sequence.
     *
     * <p>Delegates to {@link RegisterSqlPolicy#containsTopLevelSeparator}, which
     * lexes the way Postgres does: {@code ;} inside a single-quoted literal, a
     * double-quoted identifier or a {@code --} / block comment is not a separator,
     * and a quote inside a comment does not open a literal. An unterminated
     * literal or a construct it cannot model counts as a separator (fail closed).
     *
     * @param s a single vetted statement string
     * @return {@code true} if a top-level separator was found or the text cannot be proven single
     */
    static boolean containsTopLevelSeparator(String s) {
        return RegisterSqlPolicy.containsTopLevelSeparator(s);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Reads all bytes from {@code in} into a freshly allocated byte array. */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    /** Converts a byte array to a lower-case hexadecimal string. */
    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Naïve integer value extraction from a JSON string — locates {@code key}
     * and reads the numeric token after its {@code :}. Returns {@code fallback}
     * when the key is absent, the JSON is malformed, or the value is not an integer.
     * Good enough for the small, well-formed manifests the Godot plugin emits.
     *
     * @param json     the raw JSON string to search
     * @param key      the JSON key to search for (including surrounding quotes)
     * @param fallback value to return when the key is absent or unreadable
     * @return the integer value, or {@code fallback}
     */
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
     * Naïve nested-string lookup: finds the parent key, then within its
     * brace-balanced object finds the inner key's string value. Good enough
     * for the small, well-formed manifests the Godot plugin emits.
     *
     * <p><b>Not a general JSON parser</b> — do not use elsewhere.
     *
     * @param json      the raw JSON string to search
     * @param parentKey the outer key (including surrounding quotes)
     * @param innerKey  the inner key within the parent object (including quotes)
     * @return the string value, or {@code null} if absent or unparseable
     */
    private static String parseStringInObject(String json, String parentKey, String innerKey) {
        int p = json.indexOf(parentKey);
        if (p < 0) return null;
        int braceStart = json.indexOf('{', p);
        if (braceStart < 0) return null;
        // Walk brace depth to find the matching close brace for the parent object.
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

    /**
     * Best-effort recursive deletion of a staging directory.
     * Errors are silently ignored — a leaked staging directory is at most
     * a storage nuisance; the active install transaction has already failed.
     *
     * @param dir the staging directory to remove; null-safe
     */
    private static void cleanup(Path dir) {
        if (dir == null) return;
        try (var s = Files.walk(dir)) {
            // Delete deepest paths first (sorted by path-string length descending).
            s.sorted((a, b) -> b.toString().length() - a.toString().length())
             .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
}
