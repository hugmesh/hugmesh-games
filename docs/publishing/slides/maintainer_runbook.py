"""
Snapdragon Games — Maintainer / SRE runbook.

A 14-slide deck for whoever owns this stack on call: how to detect, how
to triage, how to recover. Pairs with the administration deck (which
covers planned changes); this deck is for unplanned ones.
"""
from pathlib import Path
import argparse, sys
sys.path.insert(0, str(Path(__file__).parent))
from _snapdragon_deck import make_deck   # noqa: E402


SECTIONS = [
    {
        "title": "Scope",
        "body": [
            "Day-2 incident response. Detect → triage → mitigate → recover.",
            "Change management lives in the Administration deck. This deck is for fires.",
            "Audience: on-call engineer with shell access to the compose host.",
            "Assumes: docker, kubectl-equivalent skills, comfort reading SSE/Reactor stack traces.",
        ],
    },
    {
        "title": "Health checks at a glance",
        "body": [
            "docker compose ps — every service `healthy`, not just `up`.",
            "curl /api/actuator/health — Spring Boot composite.",
            "curl /api/federation/manifest — proves host import map fetch.",
            "curl /api/game/stream — holds open with 15 s keepalive.",
            "Casdoor is internal — check via `docker compose logs casdoor`.",
        ],
        "image": "diagram:topology",
    },
    {
        "title": "Runbook 1: BFF flapping",
        "body": [
            "Symptom: `api` (or `api-native`) restarts every 30–60 s.",
            "Step 1 — `docker compose logs api --tail=200`. Look for the boot trace.",
            "Step 2 — Liquibase failure? It will be the FIRST stack trace. Read the changeset id.",
            "Step 3 — If it's a constraint violation, you may need a fixup changeset. NEVER edit a committed changeset.",
            "Step 4 — If it's the @PostConstruct race against Liquibase: confirm @DependsOn(\"liquibase\") on the affected service.",
            "Step 5 — Recovery: `docker compose stop api && docker compose start api` once the underlying issue is fixed.",
        ],
    },
    {
        "title": "Runbook 2: SSE silently drops",
        "body": [
            "Symptom: HUD freezes mid-game; no errors in BFF log.",
            "Cause 99% of the time: a Sinks.many() got autoCancel=true.",
            "Diagnosis: grep `Sinks.many` for missing autoCancel=false.",
            "Workaround: restart BFF (forces re-subscribe).",
            "If added in last commit, suspect that commit first.",
        ],
        "image": "diagram:game-stream-controller",
    },
    {
        "title": "Where to inspect: the admin dashboard",
        "body": [
            "/admin shows active sessions per character + per-user stats.",
            "Recent uploads card — quick read on what changed last.",
            "Active launches table — `kick` button DELETEs /api/admin/launches/{uid}.",
        ],
        "image": "screenshot:admin-01-dashboard",
    },
    {
        "title": "Runbook 3: Federated route 500s",
        "body": [
            "Symptom: clicking /admin or /login throws a NavigationError banner.",
            "Step 1 — Network panel: which /_remotes/ URL failed? That's your guilty remote.",
            "Step 2 — `docker compose ps <remote-name>`. If `unhealthy`, restart it.",
            "Step 3 — Check federation_modules in Postgres. A row with a stale remote_entry_url will 404.",
            "Step 4 — If the host can't fetch /api/federation/manifest, the BFF is the actual culprit (see runbook 1).",
            "Step 5 — Check nginx logs for proxy errors: `docker compose logs frontend --tail=100`.",
        ],
        "dark": True,
    },
    {
        "title": "Runbook 4: Iframe blank, no error",
        "body": [
            "Symptom: home page shows the game card but the iframe is white.",
            "Cause 1: CORP missing on wasm-fragment. Check Network → Headers for Cross-Origin-Resource-Policy.",
            "Cause 2: COOP/COEP duplicates from the upstream nginx. Check for two of either header in the response.",
            "Cause 3: Browser cached a stale index.html. Hard reload; the cache-bust `&t=...` should make this rare.",
            "Cause 4: Bundle missing on disk. Check `<gamesDir>/<slug>/<version-dir>/` exists.",
            "Recovery: rebuild wasm-fragment if headers; clear browser cache if cache; restore from snapshot if disk.",
        ],
    },
    {
        "title": "Runbook 5: Hot upload failed mid-flight",
        "body": [
            "Symptom: admin UI returned 500 on Upload; new bundle on disk but games row not updated.",
            "The install transaction is atomic in Postgres but NOT atomic vs the on-disk move.",
            "Recovery 1: GET /api/admin/games/{slug}/uploads. Latest row supersededAt=NULL is the active version.",
            "Recovery 2: If the on-disk dir exists but no row, manually delete the dir; the next upload will recreate.",
            "Recovery 3: If the row exists but the dir is missing, restore from snapshot or pin to a previous version.",
            "Prevention: GameUploadPruner only deletes superseded versions, so rolling back is always possible.",
        ],
    },
    {
        "title": "Runbook 6: Active sessions stuck",
        "body": [
            "Symptom: dashboard shows N active launches; no players actually playing.",
            "Step 1 — Check GameLaunchSweeper logs: `docker compose logs api | grep Sweeper`.",
            "Step 2 — Each row has a 15-min TTL. If the sweeper is running, expired rows close on next tick.",
            "Step 3 — If the sweeper is silent for >2 min, the BFF scheduler thread is hung. Restart api.",
            "Step 4 — Force-close from admin: DELETE /api/admin/launches/{uid} per stuck row.",
            "Step 5 — If you see a flood of orphaned launches, check if the host is closing iframes correctly on navigation.",
        ],
        "dark": True,
    },
    {
        "title": "Runbook 7: Native image fails to start",
        "body": [
            "Symptom: api-native container exits 1 within seconds; jar build (`api`) is fine.",
            "Cause 99% of the time: missing reflection hint for a new entity.",
            "Step 1 — Check the log for ClassNotFoundException or NoSuchMethodError. The class name tells you the gap.",
            "Step 2 — Add the entity to R2dbcConvertersConfig + any @RegisterReflectionForBinding on its DTOs.",
            "Step 3 — Rebuild: make build-backend-native (~6 minutes). Test locally before redeploying.",
            "Step 4 — Until fixed, fall back to the JVM image: `docker compose up -d api` (without the native overlay).",
        ],
    },
    {
        "title": "Runbook 8: Session leak between players",
        "body": [
            "Symptom: player in session A sees a player from session B on their screen.",
            "Step 1 — `./mvnw test -Dtest=SessionIsolationTest`. Failing test = regression in GameStateService.",
            "Step 2 — Confirm the recent diff didn't drop the 3-arg applyControl(event, characterId, sessionId).",
            "Step 3 — `docker compose exec api curl localhost:8080/api/admin/sessions/{leak-id}/stream` — see what's fanning out.",
            "Step 4 — Mitigation: restart BFF (drops every sink; subscribers re-create with correct ?sessionId= filter).",
            "Pinning: SessionIsolationTest + e2e/features/multiplayer/rpg-two-players.feature.",
        ],
        "dark": True,
    },
    {
        "title": "Runbook 9: Mod / publisher 403 on a game they should own",
        "body": [
            "Symptom: mod-three-wizards or publisher-three-wizards gets 403 on their named game.",
            "99% of the time: mod_game_scopes / publisher_game_scopes row missing or wrong game_slug.",
            "Casdoor owns role (necessary); Postgres owns scope (sufficient). Check both layers.",
            "Confirm role: curl /api/admin/users/{casdoorId} | jq .roles",
            "Confirm scope: psql -c \"SELECT * FROM mod_game_scopes WHERE casdoor_id='...'\"",
            "Fix: POST /api/admin/role-grants/grant-mod (or grant-publisher). 038-flatland-publisher-scopes.xml is the worked example.",
        ],
    },
    {
        "title": "Runbook 10: 'Couldn't reach X' federated remote (TLS-only)",
        "body": [
            "Symptom: ONE remote fails to load behind TLS while others work.",
            "Cause: frontend/nginx-tls.conf drifted from frontend/nginx.conf — missing /_remotes/<slug>/ proxy block.",
            "Step 1 — `diff frontend/nginx.conf frontend/nginx-tls.conf | grep _remotes`. The missing block names the guilty remote.",
            "Step 2 — Copy the missing block into nginx-tls.conf (same location prefix, same proxy_pass).",
            "Step 3 — `docker compose restart frontend` (<5 s reload).",
            "Same rule for /api/<sub-service>/ blocks (/api/rpg/, /api/vn/) — maker side services need TLS proxy too.",
        ],
    },
    {
        "title": "Backup and restore",
        "body": [
            "Postgres        — pg_dump from inside the container; `volumes/postgres-data` is the canonical source.",
            "Casdoor         — postgres-backed; included in the Postgres dump above.",
            "Cassandra       — nodetool snapshot; cache + audit log only, recovery is best-effort.",
            "Game uploads    — `app.uploads.games-dir` (default /var/snapdragon/games) on the host. Snapshot on the same schedule as Postgres.",
            "Snapshot the whole compose host VM weekly; a restore-from-VM is faster than reconciling the four sources.",
        ],
    },
    {
        "title": "Capacity guidelines",
        "body": [
            "Per-pod memory: BFF ~700 MB JVM, ~150 MB native. Cassandra ~1 GB minimum.",
            "Postgres connections: R2DBC pool default 10, JDBC (Liquibase) pool 3. Total ~13 from the BFF.",
            "Cassandra throughput: every keypress is one write. Burst-tolerant, but watch `pending compactions`.",
            "Wasm-fragment: nginx + static files. Scales with the LAN; no hot path through the BFF for asset bytes.",
            "Federation manifest: cached client-side for 60 s; one fetch per host page load + one per sweep tick.",
        ],
    },
    {
        "title": "Security posture",
        "body": [
            "Casdoor is never exposed externally. The host BFF reverse-proxies the login flow.",
            "JWTs verified against /.well-known/jwks (anonymous). NEVER /api/get-jwks (admin-only).",
            "Per-game visibility enforced by game_access at controller level — no \"client-side filtering\".",
            "Admin endpoints all require ROLE_ADMIN; the role is checked centrally, not per-method.",
            "The X-Casdoor-Id legacy header is removed; only the JWT subject is trusted.",
        ],
        "dark": True,
    },
    {
        "title": "Dashboards to watch",
        "body": [
            "BFF active-sessions count — should drift down to ~0 outside peak.",
            "BFF SSE keepalive emit rate — should be one per 15 s per connected client.",
            "Postgres connection pool gauge — ~13 baseline; sustained spikes to 20+ mean a leak.",
            "Casdoor login rate — sudden zero usually means the login remote is down.",
            "Disk usage on `app.uploads.games-dir` — GameUploadPruner caps it but nothing automatic alarms on it.",
            "rate_limit_events (Cassandra, 90s TTL): SELECT COUNT(*) trending toward zone burst = traffic being shaped.",
        ],
    },
    {
        "title": "Escalation path",
        "body": [
            "Tier 1 (you): triage with the runbooks above. Aim for mitigation in 15 min.",
            "Tier 2 (the team): the engineer who owns the affected layer. Refer to the architecture PDF for who that is by area.",
            "Tier 3 (the operator): the human with sudo on the compose host. Needed for VM-level snapshots and disk recovery.",
            "Always record what you did in the incident channel — \"I restarted X\" is enough; \"I edited Y in production\" needs a trail.",
        ],
    },
]


CLOSING = {
    "title": "Mitigate first. Investigate second.",
    "body": [
        "Restart the smallest unit that resolves the symptom; don't restart the world if a single service does it.",
        "Every runbook here was written from a real incident. Read them cold once before you have to read them under pressure.",
        "After every fire, propose a code-level fix so the next on-call doesn't see the same symptom.",
    ],
    "links": [
        "Repo: github.com/Snapdragon-Systems/snapdragon.games",
        "Administration deck: docs/publishing/slides/build/administration-manual.pptx",
        "Architecture PDF: docs/publishing/architecture/build/architecture.pdf",
    ],
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    make_deck(
        title="Snapdragon Games — Maintainer runbook",
        subtitle="Detect, triage, mitigate. In that order.",
        kicker="Maintainer / SRE runbook",
        audience="On-call engineers and SREs.",
        sections=SECTIONS,
        closing=CLOSING,
        output=args.output,
    )
    print(f"✓ wrote {args.output}")


if __name__ == "__main__":
    main()
