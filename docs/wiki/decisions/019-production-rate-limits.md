# 019 — Production posture: LAN flag + edge rate limits for ~10k concurrent users

## Context

The portal needs three distinct deployment topologies in one repo:

1. **Workstation dev** — loopback-only, every port behind 127.0.0.1 so
   a `docker compose up` on a laptop doesn't leak Postgres or the BFF
   to the network.
2. **LAN demos** — frontend bound to `0.0.0.0` so phones, tablets, or
   another workstation on the same Wi-Fi can hit the portal at
   `http://<host-ip>:<port>/`. Useful for in-person playtesting +
   debugging mobile rendering.
3. **Production** — `hugmesh.games` reachable from the internet,
   with rate limits sized for an audience of around 10 000 concurrent
   users + abuse protection on the credential-grant and admin paths.

The single docker-compose stack supports all three with two small
configuration knobs.

## Workstation + LAN: `lan.properties`

[`lan.properties`](../../../lan.properties) at the repo root is a
docker-compose env-file (literal `KEY=VALUE` lines, no boolean
indirection). It carries:

```
FRONTEND_BIND=0.0.0.0:     # 127.0.0.1: keeps the portal loopback-only
FRONTEND_PORT=8080         # host-side port (container always 8080)
POSTGRES_BIND=127.0.0.1:   # 0.0.0.0: exposes Postgres to the LAN too
```

The compose port mappings interpolate
`${FRONTEND_BIND:-127.0.0.1:}` and `${POSTGRES_BIND:-127.0.0.1:}`.
Two equivalent ways to take the file:

```bash
make lan-up                                     # recommended
docker compose --env-file lan.properties up -d  # plain compose
```

Both produce the same result because the file is a direct
docker-compose env-file — no Makefile translation step in between.
A plain `docker compose up -d` with no env-file falls back to the
loopback defaults baked into `docker-compose.yml` — exposing the
stack to the LAN stays an explicit, named action.

`make lan-info` prints the URLs the portal answers on, including
every reachable LAN IP when `FRONTEND_BIND=0.0.0.0:`. `make lan-up`
brings the stack up with the chosen posture; `make lan-up-tls`
does the same but wraps the frontend in HTTPS (self-signed cert
from `make gen-certs`, mounted via [`docker-compose.tls.yml`](../../../docker-compose.tls.yml));
`make lan-down` tears either form down.

### Earlier design (deprecated)

An earlier iteration used a `LAN_OPEN=true|false` boolean in
`lan.properties` and translated it to `FRONTEND_BIND=0.0.0.0:` / 
`127.0.0.1:` inside a Makefile shell function. That worked for `make
lan-up` but silently fell back to the loopback default when the user
ran plain `docker compose up -d` — the Makefile never fired, so the
translation never happened. The new format stores the bind prefix
directly so both invocation paths produce the same result.

## Production: `compose.prod.yml` + edge rate limits

[`compose.prod.yml`](../../../compose.prod.yml)'s frontend port
defaults to `${PROD_FRONTEND_BIND:-127.0.0.1:}8080:8080` — loopback
because the standard prod posture is an upstream LB / TLS terminator
(nginx, Caddy, ELB, Cloud LB) at `hugmesh.games` that forwards to
this host's `127.0.0.1:8080`. Set `PROD_FRONTEND_BIND=0.0.0.0:` for a
single-VM deploy that wants this nginx to face the internet directly.

Either way, the host nginx ([`frontend/nginx.conf`](../../../frontend/nginx.conf)
+ [`frontend/nginx-tls.conf`](../../../frontend/nginx-tls.conf))
carries `limit_req_zone` declarations at the http level (nginx wraps
each `conf.d/*.conf` in its default http{} block, so http-level
directives belong above the `server{}` block in this file).

### Zone sizing math

`limit_req_zone` shared memory holds ~64 bytes per tracked IP, so
**16 MB ≈ 250 000 IPs**. An audience of ~10 000 concurrent humans
behind ~20 NAT'd egress IPs each = ~200 000 distinct IPs in the
worst case. Rounding up to 16 MB per zone leaves headroom for a
Slashdot moment without rolling tracker rows out.

The auth + admin zones stay at 8 MB because they're only exercised
when a user submits the login form or hits an admin path — a much
smaller share of the IP pool.

### Per-path budgets

| zone | rate/s | burst | mode | path |
|---|---|---|---|---|
| browse | 100 | 200 | `delay=100` | `/` (SPA shell, static) |
| api | 50 | 150 | `delay=50` | `/api/*` (catch-all) |
| sse | 30 | 100 | `nodelay` | `/api/game/stream` |
| auth | 5 | 20 | `nodelay` | `/api/auth/*` |
| admin | 5 | 20 | `nodelay` | `/api/admin/*` |
| maker_api | 10 | 40 | `delay=20` | `/api/rpg/*` + `/api/vn/*` |

Plus `limit_conn conn_per_ip 40` so a single source can't open more
than 40 parallel sockets (a normal browser uses 6 + a few SSE streams;
40 is comfortably above legitimate use, low enough to backstop a
slowloris).

`delay` smooths bursts: requests over the steady rate but inside the
burst window queue up and dribble out at the steady-state pace.
`nodelay` rejects over-limit bursts immediately with 429 (we override
nginx's default 503 because most browsers don't retry 503 but many
SDKs respect 429 + `Retry-After`).

### Auth tier sizing — learned the hard way

Initial pass used `1 req/s` with burst 4. That broke real logins:
the federation runtime pulls in the login remote in parallel with
the user's `POST /api/auth/token`, and the Angular bootstrap pings
`/api/auth/me` once per remote it loads. A normal sign-in tripped
~5–8 requests in the same second per source IP — well over a
`burst=4` cap — and the user saw 429s on the third login attempt
even though the credential was correct. (The diagnostic was
mistaken at first for "auth flow broken" rather than "edge rate
limit too tight".)

Current setting is `5 req/s` with burst 20, `nodelay`. That fits
the legitimate flow (login + refresh + manifest probes) with a
~4× safety margin while still rejecting credential-stuffing fan-out
(hundreds of attempts/sec) at the edge. The BFF's pod-internal
`RateLimitFilter` + Cassandra cross-pod aggregator (decision 017)
backstops the same path with a 20-per-minute cap that counts
across the fleet — that layer is the credential-throttle. The edge
zone is the volumetric throttle.

### Why not just rely on the BFF's existing filter?

The BFF's filter parses + validates the JWT before the rate-limit
check fires (Spring Security's filter chain ordering). For an
unauthenticated DDoS attempt at 100 000 req/s, that's still 100 000
JVM threads doing JWT parsing per second. Edge `limit_req` rejects
the surplus before Netty allocates anything — orders of magnitude
cheaper.

## What this doesn't solve

- **DDoS volumetric protection**: a 100 Gbps amplification flood
  saturates the host's NIC before nginx sees a request. Upstream
  layer (Cloudflare / AWS Shield / managed LB) handles that;
  `limit_req` only protects against requests that reach the host.
- **Per-user rate limiting**: zones key on `$binary_remote_addr`,
  not on the JWT subject. A logged-in attacker can't blow past the
  IP cap, but a single user behind a corporate NAT shares the cap
  with their colleagues. Per-user caps need to move into the BFF.
- **Worker scaling**: 10 000 concurrent connections on a single
  host's nginx is comfortable; SSE streams pin a worker per
  connection, so a fleet running this stack needs sticky-session or
  per-instance scaling. The deployment overlay (compose.prod.yml or
  the k8s manifest path) handles that — not the zones here.
