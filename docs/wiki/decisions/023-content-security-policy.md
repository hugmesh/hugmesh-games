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

1. **Move the `esms-options` inline block to a separate file.** `<script type="esms-options" src="/assets/esms-options.json"></script>` (es-module-shims supports the `src` form). Drops `script-src 'unsafe-inline'`.
2. **Disable es-module-shims shimMode.** Modern browsers support native import maps; once we drop legacy-browser support, `shimMode` becomes optional. The shim only `new Function`s in shimMode. Drops `script-src 'unsafe-eval'`.
3. **Wire `ngCspNonce` on the Angular bootstrap.** `bootstrapApplication(AppComponent, { providers: [{ provide: CSP_NONCE, useValue: '<nonce>' }] })`. The nonce flows to every `<style>` Angular injects. Drops `style-src 'unsafe-inline'`.

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
