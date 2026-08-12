"""
HugMesh Games — Full-stack developer guide.

A 16-slide deck for a developer landing on the project who needs to be
productive across BFF (Java/Spring), persistence (Postgres+Liquibase,
Cassandra), and frontend (Angular host + four federated remotes). The
deck is opinionated; it points at a small number of canonical files
instead of giving an exhaustive tour.
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _hugmesh_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "Mental model",
        "body": [
            "One repo, three languages, four runtimes.",
            "BFF (Java 21 / Spring Boot 3.4 / WebFlux + R2DBC + JDBC) — owns persistence and policy.",
            "Frontend (TypeScript / Angular standalone components / Module Federation) — owns the user surface.",
            "Games (GDScript / Godot 4.6 / Rust GDExtension) — own the WASM bundles.",
            "Native AOT (GraalVM native-image) — same Java code, sub-second cold start.",
            "All five tiers ride one docker compose. There is no separate \"prod assembly\" of pieces.",
        ],
    },
    {
        "title": "How requests are shaped",
        "body": [
            "Browser hits frontend nginx; nginx routes to:",
            (1, "/_remotes/<slug>/... — matching per-remote nginx"),
            (1, "/api/... — the BFF (Spring Boot WebFlux)"),
            "Every controller is @RestController; reactive return types pin Reactor.",
            "Liquibase runs once at boot on a separate JDBC pool of size 3.",
        ],
        "image": "diagram:topology",
    },
    {
        "title": "Backend tour, in five files",
        "body": [
            "GameStreamController — the SSE merge that drives the live HUD. The autoCancel=false rule lives here.",
            "GameUploadService — the install transaction; understanding this gives you the whole hot-upload story.",
            "FederationController — the manifest endpoint that drives the host's import map. Trivial code, foundational consequence.",
            "JdbcDataSourceConfig — explains R2DBC + JDBC coexistence. One line is the @Primary HikariDataSource.",
            "R2dbcConvertersConfig — registers every entity for native-image reflection. Adding a new entity touches one place.",
        ],
        "dark": True,
    },
    {
        "title": "Frontend tour, in five files",
        "body": [
            "frontend/src/main.ts — the two-phase bootstrap. initFederation BEFORE bootstrapApplication. Don't move the order.",
            "frontend/src/app/app.routes.ts — loadRemote helper + every federated route. Adding a remote happens here.",
            "frontend/src/app/home/home.component.ts — SCENE_TYPE_TO_GAME_SLUG, launch_uid wiring, TW_LEVEL_TRANSITION listener.",
            "frontend/projects/admin/src/app/admin-remote.component.ts — pattern for a federated remote with inline DTOs.",
            "frontend/federation-base.js — the shareAll/skip helper used by host + four remotes.",
        ],
    },
    {
        "title": "Persistence: the rules",
        "body": [
            "Postgres is the canonical relational store. Cassandra is a cache + audit log.",
            "Schema is forward-only. Never edit an applied changeset; add a new one.",
            "Numbering: the next changelog gets the next integer prefix. Order is load-bearing.",
            "Every R2DBC entity has matching reflection hints in R2dbcConvertersConfig (so native-image works).",
            "Cassandra schemas live in infra/cassandra/cassandra-init.cql and are bootstrapped by an init container.",
        ],
    },
    {
        "title": "Adding an endpoint, end-to-end",
        "body": [
            "1. Add a Liquibase changeset for any new column / table.",
            "2. Add the matching R2DBC entity + repository.",
            "3. Add a controller method; reactive return types if you can, blocking only when you must.",
            "4. Update R2dbcConvertersConfig if you added an entity.",
            "5. Generate the OpenAPI client: `make gen-api`.",
            "6. Wire the call into the right Angular service or remote.",
            "7. Add a test on each side; integration test if the Liquibase migration is non-trivial.",
        ],
        "code": "make gen-api   →   regenerates frontend/src/app/api/ from /v3/api-docs",
    },
    {
        "title": "Adding a federated route",
        "body": [
            "1. New project under frontend/projects/<remote>/.",
            "2. Expose the entry component in federation.config.js.",
            "3. Insert a federation_modules row (Liquibase).",
            "4. loadRemote(...) entry in app.routes.ts.",
            "5. /_remotes/<remote>/ proxy in frontend/nginx.conf.",
            "6. Per-remote service in docker-compose.yml.",
            "7. Redeclare DTOs inside the remote — types erase.",
        ],
        "image": "diagram:federation-bootstrap",
    },
    {
        "title": "Reactive vs blocking, in practice",
        "body": [
            "WebFlux requires non-blocking IO on the dispatcher.",
            "R2DBC for runtime; JDBC + Liquibase only at boot.",
            "Wrap blocking with Mono.fromCallable.subscribeOn(boundedElastic).",
            "Sinks.many() must set autoCancel=false.",
            "Flux.merge — see GameStreamController for the 5-channel pattern.",
        ],
        "image": "diagram:game-stream-controller",
    },
    {
        "title": "Tests, by tier",
        "body": [
            "Backend unit/slice — fast, pure Java. The default for new logic.",
            "Backend integration — Postgres Testcontainer (1.21.3 pinned for docker-java API 1.44 against Docker 29).",
            "@SpringBootTest needs @DependsOn(\"liquibase\") on @PostConstruct services or you'll race the migration.",
            "Frontend Karma — every component has a spec. 73 specs at the head of the federation branch.",
            "Playwright e2e — full stack via docker compose. Slowest but catches federation cross-cutting failures.",
        ],
    },
    {
        "title": "Auth, briefly",
        "body": [
            "Casdoor is the OIDC IdP. The BFF proxies /api/auth/* so the browser never talks to Casdoor directly.",
            "JWTs verified against /.well-known/jwks (anonymous endpoint, NOT /api/get-jwks which requires admin auth).",
            "Per-game visibility: game_access table, scope=all|role|user.",
            "ROLE_ADMIN gates every endpoint under /api/admin/.",
            "Don't trust X-Casdoor-Id headers — the legacy spoofable path is gone; verify via the JWT subject.",
        ],
    },
    {
        "title": "Native image, in one slide",
        "body": [
            "make build-backend-native runs `mvn -Pnative native:compile` (~6 minutes on a workstation).",
            "Reflection hints: R2dbcConvertersConfig + Spring Boot's @RegisterReflectionForBinding on DTOs.",
            "Compose overlay: docker compose -f compose.yml -f compose.native.yml up -d.",
            "Boots in ~600 ms vs ~4 s for the JVM jar. Image is ~150 MB vs ~400 MB.",
            "If a new entity throws ClassNotFoundException at runtime, you forgot a hint.",
        ],
        "dark": True,
    },
    {
        "title": "Logging and observability",
        "body": [
            "BFF log: docker compose logs -f api  (or api-native). Look for one-line summaries from the scheduled jobs.",
            "Frontend: BrowserConsole + Network. The host shows a NavigationError banner when a remote fails to load.",
            "wasm-fragment: serves index.html + .wasm; access logs are minimal but cors-enabled.",
            "Postgres: docker compose logs postgres. Liquibase chatter is normal at boot.",
            "Cassandra: docker compose logs cassandra. Watch for `compaction` warnings on slow disks.",
        ],
    },
    {
        "title": "Per-session SSE sinks",
        "body": [
            "Single global sink leaked across sessions; current shape is ConcurrentHashMap<String, SessionSinks>.",
            "Signature: applyControl(ControlEvent event, String characterId, String sessionId).",
            "Legacy 2-arg form stays for single-player; the 3-arg form is what multiplayer-aware controllers must call.",
            "GameStreamController accepts ?sessionId= and filters subscribers accordingly.",
            "Isolation contract: SessionIsolationTest + e2e/features/multiplayer/rpg-two-players.feature.",
            "Admin observation: /api/admin/sessions/{id}/stream merges movement + world + character + view sinks per-session.",
        ],
        "image": "diagram:sessions-topology",
    },
    {
        "title": "Mod / publisher scope schema",
        "body": [
            "Roles live in Casdoor; per-game scope lives in Postgres. Two layers, both required.",
            "mod_game_scopes / publisher_game_scopes — one row per (casdoorId, gameSlug).",
            "Adding a new scope check: @PreAuthorize at controller + repo lookup in service + Gherkin rejection scenario.",
            "Admin-only mutations: /api/admin/role-grants/{grant,revoke}-{mod,publisher}.",
            "Publishers can grant mod on games they own via /api/publisher/grant-mod (same write path, caller-owns-game check).",
            "Pinning: e2e/features/security/mod-grant-gates.feature (unauth 401, plain player 403, wrong-scope publisher 403).",
        ],
        "dark": True,
    },
    {
        "title": "Rate-limit layers",
        "body": [
            "Layer 1 — nginx zones in frontend/nginx.conf + nginx-tls.conf. Edge rejection before the JVM spins up.",
            "Sized for ~10 000 concurrent users: browse 100, api 50, sse 30, auth/admin 5, maker_api 10 r/s.",
            "limit_conn conn_per_ip 40. Tripped requests return 429 (not 503) so well-behaved clients back off.",
            "Layer 2 — RateLimitFilter (per-pod) + DistributedRateLimitService (cross-pod via Cassandra rate_limit_events table, 90s TTL).",
            "Disable distributed: app.rate-limit.distributed.enabled=false. Falls back to local-only when Cassandra is unreachable.",
            "DON'T replace one layer with the other — nginx caps IP floods at the edge; the BFF filter shapes per-user across the cluster.",
        ],
    },
    {
        "title": "Security primitives",
        "body": [
            "Crypto.mintUid() — single mint for every UI-facing UID. SHA3-256(64 random bytes from BC SP800-90A DRBG). 43-char base64.",
            "Don't UUID.randomUUID() or raw SecureRandom anymore. For raw bytes: Crypto.randomBytes(n).",
            "Roles.has(jwt, 'ADMIN') / fromAuthentication(auth) / isAdmin(jwt) — centralised claim walk; don't copy.",
            "@PreAuthorize at class level for role gates; Roles helper for method-internal checks.",
            "Liquibase 036 widened session-id columns to varchar(64) for the 43-char SHA3 shape. UUID rows still fit.",
            "Session-scoped authorizeControl(casdoorId, characterId, sessionIdOpt) — pinned by GameSessionServiceTest.",
        ],
    },
    {
        "title": "Maker side services (RPG + VN)",
        "body": [
            "Two reactive Spring Boot side services with their own Postgres instances (postgres-rpg-builder, postgres-vn-builder).",
            "REST surfaces at /api/rpg/* and /api/vn/* — proxied through frontend nginx (BOTH plain + TLS confs need the block).",
            "Each side service owns its Liquibase schema end-to-end; no JPA ddl-auto, no psql-init seed.",
            "Build worker walks the project tree → Godot 4 source → godot --export-release → POST /api/admin/games/upload.",
            "Each area (RPG) / act (VN) becomes a Chapter row in the BFF. The chapter chooser handles multi-chapter games.",
            "Decision 018 covers the side-service architecture.",
        ],
        "dark": True,
    },
    {
        "title": "Common code-review patterns",
        "body": [
            "New endpoint? Check that R2dbcConvertersConfig is updated and the OpenAPI client regen ran.",
            "New SSE channel? Check Sinks.many(...).onBackpressureBuffer(N, false) — autoCancel=false MUST be there.",
            "New federated remote? Check the host's nginx.conf for the /_remotes/ block AND federation_modules row.",
            "New Liquibase changeset? Check it has the next integer prefix and is included in db.changelog-master.xml.",
            "New game? Check the four-place slug contract from the Godot guide.",
        ],
    },
    {
        "title": "What to read first",
        "body": [
            "README.md — the canonical install + run + troubleshoot guide.",
            "docs/PLAN-module-federation-and-native.md — the eight-section plan that produced the current shape.",
            "docs/publishing/architecture/build/architecture.pdf — typeset reference; chapters 2–4 are foundational.",
            "docs/publishing/javadoc-output/index.html — branded Javadoc for every controller.",
            "frontend/projects/admin/ — the cleanest example of a federated remote with real concerns.",
        ],
        "dark": True,
    },
    {
        "title": "What NOT to do",
        "body": [
            "Don't call JDBC from a WebFlux controller. Use R2DBC.",
            "Don't import @angular-architects/native-federation statically from a route handler. Use globalThis.",
            "Don't share TypeScript DTO types across federation boundaries. Redeclare per-remote.",
            "Don't bypass Liquibase. Manual SQL on a live DB will diverge from every other environment.",
            "Don't add controller logic without tests AND an updated Javadoc.",
        ],
    },
    {
        "title": "Workflow",
        "body": [
            "Branch off main. Small, focused PRs.",
            "Run `make test-backend test-frontend` before pushing.",
            "Run `make e2e` before merging anything that touches federation, auth, or upload.",
            "Open the PR with the body explaining the why, not the what — the diff covers the what.",
            "Update README, the architecture PDF, and the Javadoc when behavior changes.",
        ],
    },
]


CLOSING = {
    "title": "You can move across the stack confidently.",
    "body": [
        "The hardest parts (federation, native-image hints, R2DBC + JDBC) are already solved — don't reinvent them.",
        "When you change cross-cutting behavior, update three things: code, tests, docs.",
        "Most production fires this codebase has had came from skipping one of those three. Don't be the next one.",
    ],
    "links": [
        "Repo: github.com/hugmesh-net/hugmesh-games",
        "Architecture PDF: make docs-pdf",
        "Build everything: make docs-all",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="HugMesh Games — Full-stack developer guide",
        subtitle="From DB to browser, with the rules that hold the stack together",
        kicker="Full-stack developer guide",
        audience="Engineers shipping changes across BFF, persistence, and frontend.",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
