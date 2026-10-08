# Decision 023 — Content-Security-Policy for the federated host shell

**Status**: Accepted 2026-05-12. The first-cut policy is defence-in-depth next to the existing OWASP header set. The strict (nonce/hash-based) policy is tracked below.

## Context

The `CLAUDE.md` "Known gaps" entry said CSP needed "an inventory of the federation runtime's eval / inline-script needs first." This decision records that inventory and the policy built from it.

### What the federation runtime requires

| Surface | Need | Why |
|---|---|---|
| `es-module-shims` in shimMode | `script-src 'unsafe-eval'` | Forced by the options block in [`src/index.html`](../../../frontend/src/index.html) (first an inline `<script type="esms-options">{ "shimMode": true }</script>`, now `assets/esms-init.js`; see Step 1). The shim rewrites bare ESM specifiers (`@angular/core`, `rxjs`, …) against the federation import map with `new Function(...)`. Without `'unsafe-eval'` the host does not boot. |
| The `esms-options` inline block | `script-src 'unsafe-inline'` | The federation runtime read its options from that inline script tag. Step 1 below moved them into `assets/esms-init.js`. The token stays for three other reasons. |
| `es-module-shims` rewritten modules | `script-src blob:` | The shim materialises each rewritten ESM module as a `blob:` script. Without `blob:` the SPA blank-screens at bootstrap (observed in an e2e run on the integration branch, per the `nginx.conf` comment). |
| Angular runtime style injection | `style-src 'unsafe-inline'` | Angular's component encapsulation injects a `<style>` element per component at runtime. A strict CSP needs `ngCspNonce` on every dynamically bootstrapped module, and federated remotes make that hard. Deferred. |
| WASM workers + blob URLs | `worker-src 'self' blob:` | Godot's web build sometimes spawns blob-URL workers for threading. |
| Data + blob images | `img-src 'self' data: blob:` | Inline SVGs in some Angular components. |
| Federated iframe | `frame-src 'self'` | The `/_remotes/wasm-fragment/<slug>/` proxy serves the Godot iframe; same-origin via the host nginx proxy. |

### What `'self'` is sufficient for

| Surface | Note |
|---|---|
| `/api/*` (BFF) | Same-origin behind the host nginx — no external `connect-src` entries needed. |
| `/api/auth/*` (Casdoor reverse-proxy) | Same-origin via the BFF; the browser never talks to Casdoor directly. |
| SSE `EventSource('/api/game/stream')` | Same-origin. |

## Decision

Ship this CSP from both `frontend/nginx.conf` and `frontend/nginx-tls.conf`. Edit them together; the `CLAUDE.md` drift rule says both must carry the same headers.

```
default-src   'self';
script-src    'self' 'unsafe-eval' 'unsafe-inline' blob:;
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
report-uri    /api/security/csp-report;
report-to     csp
```

The first cut had no `blob:` and no reporting directives. Both came later. The block above is the policy in both nginx files today.

The two `'unsafe-*'` tokens are deliberately retained as load-bearing constraints. Everything else clamps to same-origin, except `blob:` for scripts, images and workers.

The TLS conf had drifted from the plain conf. It lacked `Cross-Origin-Resource-Policy`, `Strict-Transport-Security`, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` and `Permissions-Policy`. All seven (the new CSP included) are now in both files.

## Consequences

### Positive

- The browser blocks an injected `<img src=evil.example/track>` or `<iframe src=evil>`, even when the BFF's input sanitisation lets HTML through.
- `object-src 'none'` blocks `<embed>` and `<object>` entirely.
- `base-uri 'self'` stops an injected `<base href>` from re-pointing every relative URL on the page.
- `frame-ancestors 'self'` backs up `X-Frame-Options: DENY` for browsers that prefer the CSP directive.
- The TLS drift is closed. TLS deployments carry the same headers as the plain-HTTP listener.

### Negative

- `script-src` still allows `'unsafe-eval'`. A future XSS that plants attacker-controlled JS can call `eval`. We accept this because:
  - The policy is locked to `'self'`, so the planting still has to clear sanitisation.
  - The federated host needs the token. The alternative (drop es-module-shims, move to another federation runtime) is a multi-week migration.
- `'unsafe-inline'` on script and style is the same trade-off. The path to a strict CSP is below.

### Neutral

- The first cut had no `report-uri` / `report-to` directive. Both have since been added (see Follow-ups).

## Path to strict CSP (follow-up)

Three steps remove both `'unsafe-eval'` and `'unsafe-inline'`:

### Step 1 — externalise `esms-options`  🟡 PARTIAL (#54 follow-up)

The inline `<script type="esms-options">{ "shimMode": true }</script>` block in `frontend/src/index.html` is now `<script src="assets/esms-init.js"></script>`. The new file sets `window.esmsInitOptions = { shimMode: true }` BEFORE es-module-shims.js loads. es-module-shims accepts both ways of passing options (the inline `type="esms-options"` tag and the global). The native-federation builder also injects an inline `esms-options` block into the body during post-processing. A `sed -e 's|<script type="esms-options">[^<]*</script>||g'` step in `frontend/Dockerfile` strips it after the build.

**However:** the live e2e on that build showed three OTHER inline-script paths still active, so `'unsafe-inline'` could not yet come out of `script-src`. The token is still load-bearing for:

1. **`es-module-shims.js` itself injects inline `<script>` blocks** for each rewritten ESM module. Block inline scripts and the federation runtime can load no remote, so the SPA blank-screens.
2. **Angular's beasties build optimisation** emits `<link rel="stylesheet" href="…" media="print" onload="this.media='all'">`. CSP counts that inline event handler under `script-src 'unsafe-inline'`.
3. **The Godot bundle injects an inline boot script inside an `about:srcdoc` iframe.** The iframe inherits the document CSP.

Each is its own follow-up before the token can go. Move beasties to a runtime stylesheet load. Nonce the dynamic script tags es-module-shims emits, or wait for step 2 to drop shimMode. Override the CSP iframe-side on the wasm-fragment proxy. The `esms-options` move was the cheapest of the four. It remains the cleanest example of the strict-CSP migration.

### Step 2 — disable `shimMode`  🚧 BLOCKED on federation builder

`shimMode: true` is why es-module-shims calls `new Function(...)` (hence `'unsafe-eval'`). To drop it, the `@angular-architects/native-federation` build must emit plain `<script type="module">` tags instead of `<script type="module-shim">`. The builder hardcodes `module-shim`. Native browsers ignore those scripts, and the SPA fails to bootstrap.

Ways forward (each its own PR):
- An upstream patch or option on `@angular-architects/native-federation` to opt out of shimMode.
- Move off native-federation to `@module-federation/runtime` (the Webpack-derived library that emits plain `type="module"`).
- A post-build script that rewrites `type="module-shim"` → `type="module"` after the federation build. This is brittle.

Until one lands, `script-src 'unsafe-eval'` stays. `script-src 'self'` narrows it, so the worst case still needs planted code to clear sanitisation.

### Step 3 — `ngCspNonce`  🚧 BLOCKED on per-request nonce flow

Angular's component encapsulation injects a `<style>` element per component at runtime. That needs either:
- `style-src 'unsafe-inline'` (today), OR
- `style-src 'nonce-<value>'`, with a fresh nonce per request that the server writes into the CSP header AND every injected style tag (through the `CSP_NONCE` provider).

The blocker is the per-request nonce. nginx alone cannot easily generate a unique nonce and inject it into the response body, where `<app-root>`'s `ngCspNonce` attribute lives. Options:
- The BFF (Java) serves `index.html` and templates the nonce in. This moves SPA shell serving off nginx.
- An nginx Lua module (`ngx_http_lua_module`) generates the nonce and `sub_filter`s the body. This adds Lua to the image.
- A dedicated SSR or edge service templates the nonce in.

Each is a large architecture change. Deferred until the platform needs one of them for other reasons.

The steps are independent and each lands as its own PR. Together they give a "strict" CSP with no `'unsafe-*'` tokens.

## Verification

- [x] `frontend/nginx.conf` + `frontend/nginx-tls.conf` both carry the same `Content-Security-Policy` header in lockstep.
- [ ] Boot the stack (`docker compose up -d frontend` or `make lan-up`) and navigate to `/`, `/login`, `/admin`, `/rpg-maker`. No `Refused to ...` CSP violations in the browser console.
- [ ] Confirm `curl -sI http://localhost:8080/ | grep -i content-security-policy` returns the policy verbatim.
- [ ] Confirm same on the TLS listener: `curl -ksI https://localhost:8080/`.

## Follow-ups

- Strict CSP (drop both `'unsafe-*'` tokens) through the three-step plan above.
- ~~`report-to` / `report-uri` directive + a BFF endpoint that collects + aggregates violations.~~ — done. Both nginx configs ship `report-uri /api/security/csp-report` and a `Report-To: {"group":"csp",...}` header pointing at the same endpoint. `CspReportController` accepts both formats (legacy `application/csp-report`, modern `application/reports+json`) at `/api/security/csp-report` and only logs at WARN. It is allow-listed in `SecurityConfig.PUBLIC_PATHS` (as `/api/security/**`) because browsers POST reports unauthenticated. ClickHouse storage waits until the WARN stream proves useful in aggregate.
- Per-route tightening: `/login` does not need `worker-src blob:`, and `/admin` does not need `frame-src 'self'`. The gain is small until the strict policy lands.
