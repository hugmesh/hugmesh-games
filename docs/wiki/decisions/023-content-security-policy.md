# Decision 023 — Content-Security-Policy for the federated host shell

**Status**: Accepted 2026-05-12. First-cut policy lands as defence-in-depth alongside the existing OWASP header set; strict (nonce/hash-based) follow-up tracked below.

## Context

The `CLAUDE.md` "Known gaps" entry called out CSP as needing "an inventory of the federation runtime's eval / inline-script needs first." This decision documents that inventory and the policy that ships from it.

### What the federation runtime requires

| Surface | Need | Why |
|---|---|---|
| `es-module-shims` in shimMode | `script-src 'unsafe-eval'` | Forced via `<script type="esms-options">{ "shimMode": true }</script>` in [`src/index.html`](frontend/src/index.html). The shim rewrites bare ESM specifiers (`@angular/core`, `rxjs`, …) against the federation import map using `new Function(...)`. Dropping `'unsafe-eval'` breaks the host boot entirely. |
| The `esms-options` inline block | `script-src 'unsafe-inline'` | The federation runtime reads its options from that inline script tag. Could be moved to a separate JSON file (see follow-up below) to drop this token. |
| Angular runtime style injection | `style-src 'unsafe-inline'` | Angular's component-encapsulation strategy injects per-component `<style>` elements at runtime. Strict CSP requires `ngCspNonce` wired on every dynamically-bootstrapped module — federated remotes complicate that. Defer to a follow-up. |
| WASM workers + blob URLs | `worker-src 'self' blob:` | Godot's web build occasionally spawns blob-URL workers for threading. |
| Data + blob images | `img-src 'self' data: blob:` | Inline SVGs in some Angular components. |
| Federated iframe | `frame-src 'self'` | The `/_remotes/wasm-fragment/<slug>/` proxy serves the Godot iframe; same-origin via the host nginx proxy. |

### What `'self'` is sufficient for

| Surface | Note |
|---|---|
| `/api/*` (BFF) | Same-origin behind the host nginx — no external `connect-src` entries needed. |
| `/api/auth/*` (Casdoor reverse-proxy) | Same-origin via the BFF; the browser never speaks to Casdoor directly. |
| SSE `EventSource('/api/game/stream')` | Same-origin. |

## Decision

Ship the following CSP from both `frontend/nginx.conf` and `frontend/nginx-tls.conf` (lockstep edit — both must carry the same headers per the `CLAUDE.md` drift rule):

```
default-src   'self';
script-src    'self' 'unsafe-eval' 'unsafe-inline';
style-src     'self' 'unsafe-inline';
img-src       'self' data: blob:;
font-src      'self' data:;
connect-src   'self';
worker-src    'self' blob:;
frame-src     'self';
frame-ancestors 'self';
form-action   'self';
base-uri      'self';
object-src    'none';
```

The two `'unsafe-*'` tokens are deliberately retained as load-bearing constraints. Everything else clamps to same-origin.

The TLS conf had drifted from the plain conf — it was missing `Cross-Origin-Resource-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, and `Permissions-Policy`. All seven (including the new CSP) are now present in both files in lockstep.

## Consequences

### Positive

- An injected `<img src=evil.example/track>` or `<iframe src=evil>` is blocked at the browser level even if the BFF's input sanitisation lets HTML through.
- `object-src 'none'` blocks `<embed>` / `<object>` entirely.
- `base-uri 'self'` prevents an injected `<base href>` from re-pointing every relative URL on the page.
- `frame-ancestors 'self'` reinforces the existing `X-Frame-Options: DENY` for browsers that prefer the CSP directive.
- The TLS conf drift gap closed — TLS deployments now carry the same posture as the plain-HTTP listener.

### Negative

- `'unsafe-eval'` is still allowed on `script-src`. A future XSS that can plant attacker-controlled JS gets to call `eval`. Acceptable because:
  - It's locked to `'self'` — the planting itself still has to clear sanitisation.
  - The federated host genuinely needs the token; the alternative (drop es-module-shims, switch to a different federation runtime) is a multi-week migration.
- `'unsafe-inline'` on script + style same trade-off. The path to strict CSP is documented as a follow-up below.

### Neutral

- No `report-uri` / `report-to` directive in this first cut. Adding a reporting endpoint is in the follow-up list.

## Path to strict CSP (follow-up)

Three steps to drop both `'unsafe-eval'` and `'unsafe-inline'`:

### Step 1 — externalise `esms-options`  🟡 PARTIAL (#54 follow-up)

The inline `<script type="esms-options">{ "shimMode": true }</script>` block in `frontend/src/index.html` is now `<script src="assets/esms-init.js"></script>`, where the new file sets `window.esmsInitOptions = { shimMode: true }` BEFORE es-module-shims.js loads. es-module-shims accepts both option-delivery paths (inline `type="esms-options"` AND the global). The native-federation builder ALSO auto-injects an inline `esms-options` block in the body during post-processing; a `sed -e 's|<script type="esms-options">[^<]*</script>||g'` step in `frontend/Dockerfile` strips it after the build.

**However:** the live e2e on the resulting build showed three OTHER inline-script paths still active, so `'unsafe-inline'` could NOT come out of `script-src` yet. The token is documented as still load-bearing for:

1. **`es-module-shims.js` itself dynamically injects inline `<script>` blocks** for each rewritten ESM module. Blocking inline → federation runtime can't load any remote → SPA blank-screens.
2. **Angular beasties build optimisation** emits `<link rel="stylesheet" href="…" media="print" onload="this.media='all'">` — an inline event handler that CSP counts under `script-src 'unsafe-inline'`.
3. **Godot bundle injects an inline boot script inside an `about:srcdoc` iframe** — the iframe inherits the document CSP.

Each is its own follow-up before the token can come out (move beasties to runtime stylesheet load; nonce-flow the dynamic script tags es-module-shims emits — or wait for step 2 to drop shimMode entirely; iframe-side CSP override on the wasm-fragment proxy). The `esms-options` externalisation is the cheapest of the four and stays as the cleanest example of the strict-CSP migration shape.

### Step 2 — disable `shimMode`  🚧 BLOCKED on federation builder

`shimMode: true` is what makes es-module-shims call `new Function(...)` (hence `'unsafe-eval'`). Dropping it requires the `@angular-architects/native-federation` build to emit plain `<script type="module">` tags instead of `<script type="module-shim">`. The builder currently hardcodes the `module-shim` type; without it, native browsers ignore the federation entry scripts and the SPA fails to bootstrap.

Paths forward (each its own follow-up PR):
- Upstream patch / configuration option on `@angular-architects/native-federation` to opt out of shimMode.
- Migrate off native-federation onto `@module-federation/runtime` (the Webpack-derived federation library that emits plain `type="module"`).
- Post-build script that rewrites `type="module-shim"` → `type="module"` after the federation build completes (brittle).

Until one of those lands, `script-src 'unsafe-eval'` stays. The scope is narrowed by `script-src 'self'` so the worst case still requires planted code to clear sanitisation.

### Step 3 — `ngCspNonce`  🚧 BLOCKED on per-request nonce flow

Angular's component encapsulation injects per-component `<style>` elements at runtime, which need either:
- `style-src 'unsafe-inline'` (current state), OR
- `style-src 'nonce-<value>'` with a fresh nonce per request that the server writes into both the CSP header AND every dynamically-injected style tag (via `CSP_NONCE` provider).

The blocker is the nonce-per-request flow. nginx alone can't easily generate a unique nonce and inject it into the response body (which `<app-root>`'s `ngCspNonce` attribute lives on). Options:
- Have the BFF (Java) serve `index.html` and template the nonce in. Requires moving SPA shell serving off nginx.
- Use an nginx Lua module (`ngx_http_lua_module`) to generate the nonce + sub_filter the body. Adds Lua to the image.
- Have a dedicated SSR / edge service template the nonce in.

Each is a substantial architecture change. Deferred until the rest of the platform pulls in one of these capabilities for other reasons.

Each step is independent and lands as its own PR; all three together produce a "strict" CSP with no `'unsafe-*'` tokens.

## Verification

- [x] `frontend/nginx.conf` + `frontend/nginx-tls.conf` both carry the same `Content-Security-Policy` header in lockstep.
- [ ] Boot the stack (`docker compose up -d frontend` or `make lan-up`) and navigate to `/`, `/login`, `/admin`, `/three-wizards`. No `Refused to ...` CSP violations in the browser console.
- [ ] Confirm `curl -sI http://localhost:8080/ | grep -i content-security-policy` returns the policy verbatim.
- [ ] Confirm same on the TLS listener: `curl -ksI https://localhost:8080/`.

## Follow-ups

- Strict CSP (drop both `'unsafe-*'` tokens) via the three-step plan above.
- `report-to` / `report-uri` directive + a BFF endpoint that collects + aggregates violations.
- Per-route policy tightening — `/login` doesn't need `worker-src blob:`, `/admin` doesn't need `frame-src 'self'`. Cost / benefit is marginal until the strict version lands.
