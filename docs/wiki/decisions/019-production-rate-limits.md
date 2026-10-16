# 019 — Production posture: LAN flag + edge rate limits for ~10k concurrent users

## Context

One repo must serve three deployment topologies:

1. **Workstation dev** — loopback only. Every port sits behind 127.0.0.1,
   so a `docker compose up` on a laptop doesn't leak Postgres or the BFF
   to the network.
2. **LAN demos** — the frontend binds `0.0.0.0`, so phones, tablets or
   another workstation on the same Wi-Fi can reach the portal at
   `http://<host-ip>:<port>/`. This helps in-person playtests and
   mobile-rendering debugging.
3. **Production** — `hugmesh.games` is reachable from the internet, with
   rate limits sized for around 10 000 concurrent users and abuse
   protection on the credential-grant and admin paths.

The single docker-compose stack supports all three with two small
configuration knobs.

## Workstation + LAN: `lan.properties`

[`lan.properties`](../../../lan.properties) at the repo root is a
docker-compose env-file (literal `KEY=VALUE` lines, no boolean
indirection). It carries:

```
FRONTEND_BIND=0.0.0.0:     # 127.0.0.1: keeps the portal loopback-only
FRONTEND_PORT=8080         # host-side port (container 8080; 8443 with the TLS overlay)
POSTGRES_BIND=127.0.0.1:   # 0.0.0.0: exposes Postgres to the LAN too
```

The compose port mappings interpolate
`${FRONTEND_BIND:-127.0.0.1:}` and `${POSTGRES_BIND:-127.0.0.1:}`.
Two equivalent ways to use the file:

```bash
make lan-up                                     # recommended
docker compose --env-file lan.properties up -d  # plain compose
```

Both give the same result: the file is a plain env-file with no
Makefile translation step. A plain `docker compose up -d` falls back to
the loopback defaults in `docker-compose.yml`, so exposing the stack to
the LAN stays an explicit, named action.

`make lan-info` prints the URLs the portal answers on, including every
reachable LAN IP when `FRONTEND_BIND=0.0.0.0:`. `make lan-up` starts the
stack with the chosen posture. `make lan-up-tls` does the same but wraps
the frontend in HTTPS (self-signed cert from `make gen-certs`, mounted
through [`docker-compose.tls.yml`](../../../docker-compose.tls.yml)).
`make lan-down` tears either form down.

### Earlier design (deprecated)

An earlier version used a `LAN_OPEN=true|false` boolean in
`lan.properties` and translated it to `FRONTEND_BIND=0.0.0.0:` /
`127.0.0.1:` in a Makefile shell function. Plain `docker compose up -d`
never ran the Makefile, so the translation never happened and the stack
silently fell back to loopback. The new format stores the bind prefix
directly, so both paths agree.

## Production: `compose.prod.yml` + edge rate limits

[`compose.prod.yml`](../../../compose.prod.yml)'s frontend port
defaults to `${PROD_FRONTEND_BIND:-127.0.0.1:}${PROD_FRONTEND_PORT:-8080}:8080` — loopback,
because the standard prod posture is an upstream LB / TLS terminator
(nginx, Caddy, ELB, Cloud LB) at `hugmesh.games` that forwards to this
host's `127.0.0.1:8080`. Set `PROD_FRONTEND_BIND=0.0.0.0:` for a
single-VM deploy where this nginx faces the internet directly.

Either way, the host nginx ([`frontend/nginx.conf`](../../../frontend/nginx.conf)
+ [`frontend/nginx-tls.conf`](../../../frontend/nginx-tls.conf))
declares `limit_req_zone` at the http level. nginx wraps each
`conf.d/*.conf` in its default http{} block, so http-level directives
belong above the `server{}` block in this file.

### Zone sizing math

`limit_req_zone` shared memory holds ~64 bytes per tracked IP, so
**16 MB ≈ 250 000 IPs**. About 10 000 concurrent humans behind ~20 NAT'd
egress IPs each make ~200 000 distinct IPs in the worst case. 16 MB per
zone leaves headroom for a Slashdot moment.

The auth and admin zones stay at 8 MB: only login forms and admin paths
use them.

### Per-path budgets

| zone | rate/s | burst | mode | path |
|---|---|---|---|---|
| browse | 100 | 200 | `delay=100` | `/` (SPA shell, static) |
| api | 50 | 150 | `delay=50` | `/api/*` (catch-all) |
| sse | 30 | 100 | `nodelay` | `/api/game/stream` |
| auth | 5 | 20 | `nodelay` | `/api/auth/*` |
| admin | 5 | 20 | `nodelay` | `/api/admin/*` |
| maker_api | 10 | 40 | `delay=20` | `/api/rpg/*` + `/api/vn/*` |

`limit_conn conn_per_ip 40` also caps a single source at 40 parallel
sockets. A normal browser uses 6 plus a few SSE streams, so 40 is well
above legitimate use and low enough to backstop a slowloris.

`delay` smooths bursts: requests over the steady rate but inside the
burst window queue and dribble out at the steady pace. `nodelay` rejects
over-limit bursts at once with 429. We override nginx's default 503
because most browsers don't retry 503, while many SDKs respect 429 +
`Retry-After`.

### Auth tier sizing — learned the hard way

The first pass used `1 req/s` with burst 4. It broke real logins. The
federation runtime loads the login remote in parallel with the user's
`POST /api/auth/token`, and the Angular bootstrap pings `/api/auth/me`
once per remote it loads. A normal sign-in fired ~5–8 requests in one
second from one IP, well over `burst=4`. Users saw 429s on the third
attempt even with a correct credential. We first blamed the auth flow,
not the limit.

The current setting is `5 req/s` with burst 20, `nodelay`. That fits
the legitimate flow (login + refresh + manifest probes) with a ~4×
safety margin and still rejects credential-stuffing fan-out (hundreds of
attempts per second) at the edge. The BFF's pod-internal
`RateLimitFilter` plus the Cassandra cross-pod aggregator (decision 017)
backstop the same path with a 20-per-minute cap counted across the
fleet. That layer is the credential throttle. The edge zone is the
volumetric throttle.

### Why not just rely on the BFF's existing filter?

The BFF's filter parses and validates the JWT before the rate-limit
check fires (Spring Security's filter chain ordering). For an
unauthenticated DDoS at 100 000 req/s, that is still 100 000 JVM threads
parsing JWTs per second. Edge `limit_req` rejects the surplus before
Netty allocates anything, which is orders of magnitude cheaper.

## What this doesn't solve

- **DDoS volumetric protection**: a 100 Gbps amplification flood
  saturates the host's NIC before nginx sees a request. An upstream
  layer (Cloudflare / AWS Shield / managed LB) handles that;
  `limit_req` only protects against requests that reach the host.
- **Per-user rate limiting**: zones key on `$binary_remote_addr`, not on
  the JWT subject. A logged-in attacker can't exceed the IP cap, but a
  user behind a corporate NAT shares the cap with their colleagues.
  Per-user caps must move into the BFF.
- **Client address behind a proxy**: `frontend/nginx.conf` has no
  `real_ip` configuration, so behind an upstream load balancer every
  zone keys on the balancer's address. All clients then share one
  bucket per zone. `compose.prod.yml` says the zones still work per
  client if the balancer forwards `X-Forwarded-For`; without
  `set_real_ip_from` they do not.
- **Worker scaling**: 10 000 concurrent connections on one host's nginx
  is comfortable. SSE streams pin a worker per connection, so a fleet
  running this stack needs sticky sessions or per-instance scaling. The
  deployment overlay (compose.prod.yml or the k8s manifest path) handles
  that, not these zones.
