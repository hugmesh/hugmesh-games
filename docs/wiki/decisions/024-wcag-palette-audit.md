# 024 — WCAG 2.1 AA palette audit + centralised colour tokens

## Context

The palette grew one component at a time. Each `*.component.scss`
defined its own `$muted` / `$accent` SCSS variables, and each federated
remote pasted the same hex literals into its template-string styles.
Two values showed up almost everywhere as text colour:

- `#7070a0` — muted body text (subtitles, hints, table cells, badges,
  placeholders).
- `#7c4dff` — accent text (`.login-link`), button backgrounds, focus
  rings, decorative borders.

Checked against the three shell backgrounds the SPA paints —
`#0a0a12`, `#0a0a0f`, `#12121e` — both fail WCAG 2.1 AA for normal
body text:

| swatch    | role               | bg `#0a0a12` | AA-normal ≥4.5 |
| --------- | ------------------ | -----------: | :------------- |
| `#e8e8f0` | primary text       |       15.9:1 | ✅ AAA          |
| `#7070a0` | muted text         |        3.88:1 | ❌ FAILS        |
| `#7c4dff` | accent (as text)   |        3.22:1 | ❌ FAILS        |
| `#b388ff` | accent-soft        |        7.38:1 | ✅ AAA          |
| `#4caf50` | success            |        7.10:1 | ✅ AAA          |
| `#ff6b6b` | error              |        6.34:1 | ✅ AAA          |

The two failing tokens set most of the secondary text on the host
shell, on every login / registration / banned / producer-registration
remote, and in the admin dashboard tables. A visitor who is not signed
in sees the subtitle "Sign in to enter the portal", the placeholder and
the "Federated remote" footnote on `/login`. All three used the muted
token, so every WCAG-AA audit of the public landing flow found the
same fault.

## Decision

Two changes shipped on `feature/wcag-contrast-audit`:

1. **Centralised palette in [`frontend/src/styles.scss`](../../../frontend/src/styles.scss)
   as CSS custom properties on `:root`**, each with its measured
   contrast ratio against `#0a0a12` in the source comment. A future
   contrast regression then shows at the token, not in a component
   three folders down.

2. **Replaced the two failing tokens at every text site**:
   - `#7070a0 → #a8acc4` (8.77:1 — AAA on `#0a0a12`).
   - `#7c4dff → #b388ff` for *text* only. Backgrounds, borders,
     box-shadows and focus rings keep `#7c4dff`: the 4.5:1 text rule
     does not apply to them (WCAG 1.4.11 sets 3:1 for non-text
     contrast, which `#7c4dff` still clears against any of the three
     card/shell backgrounds).

The bulk swap covered:

```
frontend/src/app/{app,game-canvas,game-menu,home,player-dashboard}/
frontend/projects/{login,admin,banned,registration,producer-registration}/
```

23 fresh `#a8acc4` text uses, plus the single `.login-link` accent-
text demotion in `app.component.scss`.

## What the tokens look like

```scss
:root {
  --hug-bg-0:        #0a0a12; /* shell + login backdrop */
  --hug-bg-1:        #12121e; /* cards, panels */
  --hug-bg-2:        #1a1a2e; /* scrollbar trough */
  --hug-border:      #1e1e32;
  --hug-text:        #e8e8f0; /* 15.9:1 AAA */
  --hug-text-muted:  #a8acc4; /*  8.77:1 AAA — replaces #7070a0 (3.88:1) */
  --hug-accent:      #7c4dff; /* backgrounds/borders only — 3.22:1 as text */
  --hug-accent-soft: #b388ff; /*  7.38:1 AAA — use this for accent text */
  --hug-success:     #4caf50;
  --hug-error:       #ff6b6b;
}
```

The sweep replaced literal hex values. It did not substitute `var(...)`,
which kept the diff narrow and reviewable. The tokens give the next
component refactor a place to migrate to. Once a component is ported,
designers can retune it from one place.

## Why two failing values, not a full repaint

WCAG-AA is a pass/fail gate. Tokens that already cleared 4.5:1
(`#e8e8f0`, `#4caf50`, `#ff6b6b`, `#b388ff`) owe nothing, and
re-tinting them risks the brand look for no accessibility gain.
The audit fixes the two values that fail. It ships them behind named
tokens, so a later designer-led repaint can move the whole palette
without re-auditing each component.

## Out of scope

- **AAA-large** (≥3:1) is the spec for headlines and bold ≥18pt. The
  display-size accent gradient stays as it is: it is painted through
  `-webkit-background-clip: text` over the brand gradient and already
  meets AAA-large.
- **Focus rings and button hover states** stay at `#7c4dff`. Rule 1.4.11
  (3:1) governs UI-component contrast, and they clear it.
- **Iframe-rendered Godot scenes.** The WASM bundles paint their own
  scene at WebGL/Canvas-2D level, and this audit does not reach inside
  the iframe. Each `games/<slug>/` source tree owns its palette audit.

## Follow-ups

- Migrate component styles to `var(--hug-text-muted)` /
  `var(--hug-accent)` instead of the literal. Do it per component, when
  the file is touched for another reason. There is no hurry: the
  literals already match the tokens. Status today: three files still declare
  `$muted` / `$accent` (`game-menu`, `player-dashboard` and `home`
  component SCSS, with the new hex values). `home.component.scss` and
  `about.component.ts` also use `var(--hug-…, <hex>)`. Ten files in all
  hold the bare literal `#a8acc4`.
- Document the tokens in `frontend/README.md`, so contributors use the
  named token instead of pasting hex into a new component. Still open:
  `frontend/README.md` does not exist.
- ~~Once decisions 020-023 (uploads / Kafka / replica / CSP) merge,
  remove the strike-through item from `CLAUDE.md` "Known gaps / TODO".~~
  — superseded. All four decisions merged; the strike-through bullets
  stay in `CLAUDE.md` as a changelog of completed platform work
  (institutional memory) rather than being deleted. The section header
  remains `Known gaps / TODO` so future un-struck bullets read as live
  work.
