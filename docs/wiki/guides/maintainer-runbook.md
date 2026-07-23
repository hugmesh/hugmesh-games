# Maintainer runbook

> Mirror of [`docs/publishing/userguides/maintainer-runbook/guide.tex`](../../publishing/userguides/maintainer-runbook/guide.tex).

Detect, triage, mitigate. The runbook for whoever owns the live stack on call.

## Scope

Day-2 incident response. Detect → triage → mitigate → recover. Change management lives in the [administration manual](administration.md); this is for fires.

Audience: on-call engineer with shell access to the compose host. Assumes Docker, kubectl-equivalent skills, comfort reading SSE / Reactor stack traces.

## Health checks at a glance

```bash
docker compose ps                            # every service should be `healthy` (not `up`)
curl /api/actuator/health                    # Spring Boot health composite
curl /api/federation/manifest                # 200 with non-empty `remotes` list
curl -N /api/game/stream                     # holds open with `ping` keepalive every 15s
docker compose logs casdoor                  # casdoor is internal; only logs to inspect
```

## Runbook 1 — BFF flapping

**Symptom.** `api` (or `api-native`) restarts every 30–60 s.

1. `docker compose logs api --tail=200`. Look for the boot trace.
2. Liquibase failure? It will be the FIRST stack trace. Read the changeset id.
   - `column X.Y does not exist` → A previous rename missed a table/column. Forensic query: `SELECT table_name, column_name FROM information_schema.columns WHERE column_name LIKE '%xxx%'`. Append a follow-up `<renameColumn>` changeset with `<preConditions onFail="MARK_RAN">`.
   - `Unterminated dollar quote` / `syntax error at or near "$1"` from a `<sql>` block → JDBC PreparedStatement binder ate a `?` jsonb operator. Use `jsonb_exists(col, 'key')` instead of `col ? 'key'`.
   - Constraint violation? You may need a fixup changeset. **Never edit a committed changeset.**
3. `PropertyReferenceException: No property 'X' found for type 'Y'` → Spring Data parsed a derived query method name (`findByLevelId`) against an entity whose property was renamed (`chapterId`). Fix: rename the repo method to match.
4. `@PostConstruct` race against Liquibase? Confirm `@DependsOn("liquibase")` on the affected service.
5. Casdoor secret mismatch (BFF can't fetch JWKS) → re-pin `.env` from `docker compose logs casdoor`. See [the build & deploy chapter](../architecture/06-build-and-deploy.md#-casdoor-seed-credentials).

## Runbook 2 — SSE silently drops

**Symptom.** HUD freezes mid-game; no errors in the BFF log.

99% of the time: a `Sinks.many()` got `autoCancel=true` (default). See [decision 003](../decisions/003-autocancel-false.md).

**Diagnosis:** grep the relevant service for `Sinks.many` and verify every call passes `autoCancel=false`.
**Workaround:** restart the BFF (kills sinks, forces re-subscribe).
**Fix:** lives in the source.

## Runbook 3 — Federated route 500s

**Symptom.** Clicking `/admin` or `/login` throws a NavigationError banner ("Couldn't reach X — the federated module didn't load").

1. Network panel: which `/_remotes/` URL failed? That's your guilty remote.
2. `docker compose ps <remote>`. If `unhealthy`, restart it.
3. Check `federation_modules` in Postgres. A row with a stale `remote_entry_url` will 404.
4. If the host can't fetch `/api/federation/manifest`, the BFF is the actual culprit (Runbook 1).

## Runbook 4 — Iframe blank, no error

**Symptom.** Home page shows the game card but the iframe is white.

| Cause | Verify | Fix |
|---|---|---|
| CORP missing on `wasm-fragment` | Network → Headers shows no `Cross-Origin-Resource-Policy` | Add `add_header Cross-Origin-Resource-Policy cross-origin` |
| COOP/COEP duplicates from upstream nginx | DevTools shows `NotSameOriginAfterDefaultedToSameOriginByCoep` | Host nginx must `proxy_hide_header` upstream pair, re-emit canonical |
| Browser cached a stale `index.html` | Ctrl+Shift+R fixes it | The cache-bust `&t=...` should make this rare |
| Bundle missing on disk | `<gamesDir>/<slug>/<version-dir>/` doesn't exist | Restore from snapshot or pin to a previous version |
| `ERROR: Cannot get class 'HostBridge'` in console | Rust GDExtension WASM not in bundle | `make build-rust && make build-godot`. Confirm `extensions_support=true` and no `*.gdextension` exclude filter. |

## Runbook 5 — Hot upload failed mid-flight

**Symptom.** Admin UI returned 500 on Upload; new bundle on disk but `games` row not updated.

The install transaction is atomic in Postgres but **NOT** atomic vs the on-disk move. Recovery:

- `GET /api/admin/games/{slug}/uploads`. Latest row `supersededAt=NULL` is the active version.
- If on-disk dir exists but no row, manually delete the dir; the next upload will recreate.
- If row exists but dir is missing, restore from snapshot or pin to a previous version.

## Runbook 6 — No games showing for a player

**Symptom.** Logged-in player sees an empty home page; admin sees the games normally.

1. `curl /api/games/available/{characterId}` with the player's JWT — if it's empty, the access policy is locking them out.
2. `SELECT * FROM game_access WHERE game_slug = '<slug>'` — verify there's at least one row matching the player (an `(all, NULL)` row, or a `(role, ROLE_PLAY_X)` row whose role they hold).
3. If a chapter prerequisite is in play, `SELECT * FROM character_quests WHERE character_id = ?` — incomplete prerequisite quests lock chapters.
4. After a Liquibase rename PR, check `chapter_prerequisites` exists and the column is `chapter_id` not `level_id`. PR #24's 028-9/10/11 added the missed rename. The 028-12 follow-up also renamed `scene_elements.level_id → chapter_id`.

## Runbook 7 — Player can't move

**Symptom.** Player sees the game render but key/mouse input doesn't reach Godot.

See [Godot developer guide → When movement doesn't happen](godot-developer.md#when-movement-doesnt-happen-in-the-wasm-iframe).

## Runbook 8 — Empty world (no scene elements spawn)

**Symptom.** Game loads, the player drops onto the floor, but the world is barren — no torches, fairies, geometry, lights. Browser console shows `[GameBridge] scene already prefetched, skipping fetch` (normal) and either `[TW] scene prefetched: undefined — 0 elements` or `[TW] scene prefetch failed HTTP 500`.

1. Curl the scene endpoint directly: `curl -i http://localhost:8080/api/chapters/1/scene`. A 500 here is the smoking gun.
2. Tail the BFF: `docker compose logs api | tail -50`. Look for `BadSqlGrammarException: column scene_elements.chapter_id does not exist` — Liquibase 028-12 didn't run.
3. Re-apply migrations: `docker compose restart api`. Liquibase will pick up 028-12 and the column rename completes.
4. Hard-refresh the browser (`Ctrl+Shift+R`) — the next page load re-runs `session.connect()` and the next scene fetch returns the populated elements list.

If the column already exists (`SELECT column_name FROM information_schema.columns WHERE table_name = 'scene_elements'` lists `chapter_id` not `level_id`) but elements are still empty, check whether the seed migration (004) has been re-run — `SELECT COUNT(*) FROM scene_elements WHERE chapter_id = 1` should return non-zero.

## Runbook 9 — 403 on `/api/controls/{id}` after a BFF restart

**Symptom.** Network tab shows `POST /api/controls/1 → 403 Forbidden` on every keypress. The world rendered fine before the restart.

The legacy single-player controller role is held in-memory (`SessionService.currentController`); a BFF restart wipes it. Auto-recovery is now wired in:

1. `game_bridge.gd` detects the 403 in `send_control` and posts `SD_SESSION_EXPIRED` to the host shell.
2. `ApiBridgeService.handleSessionExpired()` re-runs `session.connect()` → `session.claimControl()`.
3. The next keypress succeeds.

If the 403 persists past the second keypress, the auto-recovery itself failed — check the browser console for `[session] connect failed` warnings, hard-refresh once, and confirm the player isn't blocked from the game by `game_access`. Multi-tab play ate the controller role only if another tab in the same Casdoor session claimed it; close stragglers.

## Backup, capacity, escalation

**Backup.** `pg_dump` from inside the postgres container nightly; snapshot `app.uploads.games-dir` on the same schedule.

**Capacity.** BFF ≈700 MB JVM, ≈150 MB native. Postgres connections ≈13 baseline; sustained spike to 20+ means a leak.

**Escalation.**
- **Tier 1 (you):** triage with these runbooks. Aim for mitigation in 15 min.
- **Tier 2 (the team):** the engineer who owns the affected layer.
- **Tier 3 (the operator):** the human with sudo on the compose host.

## LAN exposure (workstation demos)

Default `make up` binds the frontend to `127.0.0.1` only — every other device on the local Wi-Fi gets `Connection refused`. To open it for a demo:

1. Edit [`lan.properties`](../../../lan.properties): set `FRONTEND_BIND=0.0.0.0:` (keep the trailing `:` — it's spliced into the port mapping). `lan.properties` is a docker-compose env-file — `FRONTEND_BIND` / `FRONTEND_PORT` / `POSTGRES_BIND` are literal KEY=VALUE lines.
2. Bring the stack up one of two equivalent ways:
   - `make lan-up` — recommended, includes a post-up `lan-info` print of reachable URLs.
   - `docker compose --env-file lan.properties up -d` — plain compose, same result.
3. `make lan-info` — prints every reachable LAN IP + the portal URL.
4. When done, set `FRONTEND_BIND=127.0.0.1:` and re-run `make lan-up` (or `make lan-down` to stop the stack entirely).

For HTTPS over the same LAN access pattern (useful when testing OAuth flows / Service Workers that require secure context):

1. `make gen-certs` — writes self-signed CA + server cert to `./e2e/certs/`. The script now detects the host's LAN IPv4 addresses and stuffs them into the cert's SAN list, so `https://<lan-ip>:8080/` is name-valid (not just `https://localhost/`).
2. `make lan-up-tls` — same `lan.properties` env file, plus the [`docker-compose.tls.yml`](../../../docker-compose.tls.yml) overlay which mounts `nginx-tls.conf` and remaps host port 8080 → container 8443.
3. Browsers will prompt because the CA is self-signed; accept the warning once. To skip the prompt entirely, import `e2e/certs/ca.crt` into the system trust store.

Casdoor login is password-grant (no OAuth redirect URI to whitelist), so LAN clients log in normally. Postgres + Casdoor stay loopback regardless; set `POSTGRES_BIND=0.0.0.0:` for one-off DB-GUI access from a peer machine — rarely needed and a security footgun.

## Production exposure + rate-limit knobs

`compose.prod.yml` defaults the frontend to `127.0.0.1:8080` (assumes upstream LB / TLS terminator at `hugmesh.games`). Set `PROD_FRONTEND_BIND=0.0.0.0:` on the deploy host when there is no upstream proxy and this nginx should face the internet directly.

The host nginx (`frontend/nginx.conf` + `frontend/nginx-tls.conf`) ships `limit_req_zone` declarations sized for ~10 000 concurrent users — see [decision 019](../decisions/019-production-rate-limits.md) for the budgets per path. Tripped requests return 429 (not 503) so well-behaved clients back off.

If a budget is being hit legitimately under load:

1. Watch the `429` rate in nginx access logs (or edge-LB observability) to identify the saturated zone.
2. Bump the `rate=` for that zone in both `nginx.conf` AND `nginx-tls.conf` (these two files MUST stay in lockstep — the e2e + LAN-TLS overlays both mount `nginx-tls.conf`, and drift between the two files masquerades as "feature X works in dev but not under HTTPS").
3. Re-deploy the frontend image; the BFF / Cassandra rate-limit layer is independent and doesn't need touching for an nginx-tier change.

The `auth` zone was bumped from `1 r/s burst=4` to `5 r/s burst=20` after the original tighter setting locked out real logins — the federation runtime + Angular bootstrap fire ~5–8 `/api/auth/*` requests per legitimate sign-in, well over the original cap. The BFF's Cassandra-backed credential throttle (decision 017, 20-per-minute fleet-wide) is the dedicated brute-force defense; the edge zone is the volumetric floor.

If a budget is being hit legitimately under load:

1. Watch the `429` rate in nginx access logs (or your edge-LB observability) to identify the saturated zone.
2. Bump the `rate=` for that zone in **both** `nginx.conf` AND `nginx-tls.conf` (the e2e overlay must stay in lockstep — see the drift gotcha in CLAUDE.md).
3. Re-deploy the frontend image; the BFF's pod-internal + Cassandra cross-pod rate-limit layer is independent and doesn't need touching for an nginx-tier change.
