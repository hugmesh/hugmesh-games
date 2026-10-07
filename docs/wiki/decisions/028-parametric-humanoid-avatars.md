# 028 — Parametric humanoid avatars (N64-style low-poly, cross-service)

**Status:** accepted
**Supersedes (extends):** the character-maker slice of [027](027-biome-themes-and-real-godot-e2e.md)

## Context

The character maker that landed with the biome-worlds work gives a project **one**
hero avatar built from seven whitelisted fields — `body_shape` (slim/stocky/round),
`head_shape` (round/square), `hat_type` (none/cap/cone/top_hat), a Y-only
`height_scale`, and three colours. `avatar_factory.gd` turns that into three
floating primitives: a tapered cylinder "robe", a sphere/box head, and an optional
hat.

That model cannot express what we now need, and the gaps are structural rather
than cosmetic. This list describes the code before this decision:

- **There are no limbs.** No arms, no legs, no hands, no feet — so "vary chest,
  height, leg height and all limbs within humanoid bounds" has nothing to vary.
- **There is no rig.** Every avatar in this repo (`avatar_factory.gd`,
  `room_builder._build_npc`, `multiplayer_lobby.rs`) is a flat bag of
  `MeshInstance3D`. A repo-wide grep for `AnimationPlayer|AnimationTree|Skeleton3D|
  BoneAttachment` returns **nothing**. Walking, gestures and expressions are not
  "turn on animation" — there is no articulated structure to animate.
- **The 2D and 3D sides are independent hard-coded geometry tables.**
  `avatar_factory.gd` (GDScript primitives) and `drawAvatarPreview()`
  (`rpg-maker-remote.component.ts`, canvas 2D) each hard-code their own numbers.
  They already disagree and can drift silently on every change. (The canvas
  routine is gone: the maker now POSTs the config to `/api/rpg/avatar-preview.svg`
  and shows the returned SVG.)
- **The platform has no user-avatar concept at all** (now it has: see Cross-service avatars). `avatarUrl` /
  `profile_picture` / `photoUrl` appear **zero** times across `frontend/src`,
  `frontend/projects` and `backend/src`. `player_characters` stores stats only.
- **Nothing is exportable.** No `.glb`/`.gltf` exists in the repo and nothing
  imports one.

## Decision

Introduce **one canonical, pure-Java character model** — package
`games.hugmesh.character` — that is the single source of truth for *every*
representation of a character: the 3D Godot rig, the 2D SVG, the glTF export, and
the portable interchange document. Every surface derives from it; none re-derives
geometry independently.

```
                        CharacterSpec  (validated params + humanoid bounds)
                              │
                        HumanoidRig    (spec → named joint tree + primitives)
                              │
        ┌─────────────────┬───┴───────────┬────────────────────┐
   GdRigBaker         AvatarSvgRenderer  GlbExporter     CharacterDocument
   (avatar_config.gd)      (.svg)          (.glb)          (portable JSON)
        │                    │               │                   │
   Godot 3D rig        2D preview,      Blender / any      import/export,
   + avatar_poser      expression        engine, texture   other generators,
   (walk/gesture)      stills            re-authoring      other services
```

**The 2D SVG matches the 3D model because it is an orthographic projection of the
same rig**, not a parallel drawing routine. This is the mechanism that satisfies
"make the 2d svg side match as intended" — parity is structural, not maintained
by discipline.

### Why a synced copy rather than a shared Maven module

`backend/`, `rpg-builder/` and `vn-builder/` are three independent Maven projects,
each Dockerised with **its own directory as the build context**
(`build: ./rpg-builder`). A shared library would require an aggregator pom, a
repo-root build context for all three images, and `mvn install` ordering — invasive
build surgery.

Instead the model is **canonical in `rpg-builder`** and synced into the consumers
by `make sync-character-model`, mirroring the existing `make sync-bridge-addon`
idiom. The package is deliberately `games.hugmesh.character` (not
`…rpgbuilder.…`) to mark it portable, and it has **no Spring and no third-party
dependencies**, so it copies verbatim.

> **Status: live.** `backend/` and `vn-builder/` both carry the copy, each
> guarded by a byte-identical `CharacterModelSyncTest` that fails the service's
> own test run on any divergence — changed bytes, missing files, files added at
> any depth, or a missing canonical tree (fail, never skip). `build-backend`
> runs the sync as a prerequisite. The converse cost of the contract: editing a
> canonical file without re-running `make sync-character-model` turns both
> consumers' suites red — which is the guard doing its job.

## The parameter set

All fields are optional on the wire; an omitted field takes its column default.
A **present** out-of-range value is a `400`, never silently clamped (the existing
`heightScale` rule, extended to every numeric — boxed types + reject keeps an
omitted primitive from becoming a wrong non-default).

### Proportions — "reasonable humanoid bounds"

| field | min | max | default | effect |
|---|---|---|---|---|
| `heightScale` | 0.70 | 1.40 | 1.00 | overall stature (Y) |
| `headScale` | 0.70 | 1.50 | 1.00 | head size — 1.5 is chibi, 0.7 realistic-small |
| `neckScale` | 0.60 | 1.60 | 1.00 | neck length/thickness |
| `chestScale` | 0.70 | 1.40 | 1.00 | chest/upper-torso depth+width |
| `waistScale` | 0.60 | 1.50 | 1.00 | waist width |
| `shoulderScale` | 0.70 | 1.40 | 1.00 | shoulder span |
| `armLengthScale` | 0.75 | 1.30 | 1.00 | upper arm + forearm |
| `armThicknessScale` | 0.60 | 1.50 | 1.00 | arm girth |
| `legLengthScale` | 0.75 | 1.30 | 1.00 | thigh + shin ("leg height") |
| `legThicknessScale` | 0.60 | 1.50 | 1.00 | leg girth |
| `handScale` | 0.60 | 1.60 | 1.00 | hand size |
| `footScale` | 0.60 | 1.60 | 1.00 | foot size |

The bounds are the "reasonable humanoid" contract: the extremes stay recognisably
bipedal — no zero-length limbs, no limbs longer than the body, no head larger than
the torso. They are mirrored in several places that must move together (see Consequences).

### Shape tokens

| field | values | default |
|---|---|---|
| `renderStyle` | `smooth`, `lowpoly` | `smooth` |
| `bodyShape` | `slim`, `stocky`, `round` | `slim` |
| `headShape` | `round`, `square`, `oval`, `tapered` | `round` |
| `hairStyle` | `none`, `short`, `long`, `ponytail`, `spiky`, `bun`, `braid` | `short` |
| `outfitType` | `robe`, `tunic`, `armor`, `dress`, `jumpsuit`, `bare` | `robe` |

`renderStyle=lowpoly` is the **N64 mode**: radial segment counts drop from 16
to 6 and rings from 8 to 3 (`avatar_factory.gd`), materials switch to per-vertex shading with specular off and
`TEXTURE_FILTER_NEAREST`. This is not a stylistic filter bolted on top — it is how
the rig is *built*, and it matches how N64 characters actually were built: rigid
segmented limbs pivoting at plain joints, which is exactly the rig below.

### Item slots — "all items where possible"

| slot | values | default |
|---|---|---|
| `hatType` | `none`, `cap`, `cone`, `top_hat`, `helm`, `crown`, `hood`, `bandana`, `straw` | `none` |
| `heldMain` (right hand) | `none`, `sword`, `staff`, `axe`, `bow`, `wand`, `pickaxe`, `lantern`, `book` | `none` |
| `heldOff` (left hand) | `none`, `shield`, `torch`, `book`, `lantern`, `orb` | `none` |
| `backItem` | `none`, `cape`, `backpack`, `wings`, `quiver`, `scabbard` | `none` |
| `faceItem` | `none`, `glasses`, `mask`, `visor`, `eyepatch` | `none` |

### Colours (`^#[0-9a-fA-F]{6}$`) and texture

`bodyColor` (outfit primary, `#3f59b3`), `headColor` (skin, `#e6c8a0`),
`hatColor` (`#202020`), `hairColor` (`#3a2a1c`), `accentColor` (outfit trim,
`#d8b45a`), `itemColor` (held/back items, `#8a8f98`), `eyeColor` (`#2b2b33`).

`skinTextureAssetId` — nullable UUID referencing an uploaded project asset,
applied as albedo to the `skin` and `outfit` texture slots. Uses the existing
`AssetStorageService` (mime whitelist, 4 MB cap, cross-project IDOR filter).

### Expressions

`neutral`, `happy`, `sad`, `angry`, `surprised`, `wink`, `sleepy`, `determined`,
`shout`. Expression is **render-time**, not stored geometry: it is a table of
transforms applied to the five face joints (`EyeL`, `EyeR`, `BrowL`, `BrowR`,
`Mouth`). The *same* table drives the Godot poser and the SVG renderer, so an
expression still and the in-game face agree.

## The rig

Joint nodes are plain `Node3D`s; meshes hang off them. No `Skeleton3D`, no skinning
— segmented rigid limbs, which keeps the N64 look honest, keeps the GDScript
trivial, and animates by rotating joints.

```
Root
└─ Hips ─────────────────────── pelvis
   ├─ Torso ─────────────────── waist
   │  └─ Chest ──────────────── chest, backItem
   │     ├─ Neck ─── Head ───── skull, hair, hat, faceItem
   │     │                      EyeL EyeR BrowL BrowR Mouth   ← expression targets
   │     ├─ ShoulderL ─ UpperArmL ─ ForearmL ─ HandL ── heldOff
   │     └─ ShoulderR ─ UpperArmR ─ ForearmR ─ HandR ── heldMain
   ├─ ThighL ─ ShinL ─ FootL
   └─ ThighR ─ ShinR ─ FootR
```

### Baked contract (`scripts/avatar_config.gd`)

The renderer computes the rig **in Java** and bakes the result; `avatar_factory.gd`
becomes a dumb instantiator that walks the baked tree. GDScript never re-derives a
single dimension, so 3D/2D/glTF cannot diverge.

```gdscript
const AVATAR := { … raw tokens + Color() values … }
const STYLE  := "lowpoly"
const RIG    := {"name":"Root","pos":Vector3(0,0,0),"parts":[…],"children":[…]}
const EXPRESSIONS := {"happy":{"Mouth":{"pos":Vector3(…),"scale":Vector3(…)}, …}, …}
```

A part is `{"name", "shape":"box|cyl|sphere|quad", "pos":Vector3,
"size":Vector3, "rot":Vector3, "taper":float, "color":Color,
"tex":"skin|outfit|none", "slot":"body|hair|hat|face|back|held_main|held_off"}`.
Raw `#rrggbb` strings still never reach GDScript (pinned by
`RpgGodotProjectRendererTest`).

### Animation (`avatar_poser.gd`)

Procedural, driven per-frame from the body's own velocity — no animation assets,
no import pipeline, and it works identically for the local hero and every remote
avatar:

- **idle** — chest breathing, slight arm sway
- **walk / run** — thigh/shin/arm counter-swing on a distance-keyed phase, body bob
- **gestures** — `wave`, `point`, `nod`, `bow`, `clap`, `shrug`, `cheer`, `dance`

Gestures relay over the existing control/SSE channel as `GESTURE:<name>` (same
server-observable shape as `SHOP_BUY` / `TALK_NPC`), so remote players see them.

## Cross-service avatars

New in the BFF: `user_avatars` (many named avatars per user, each storing a
Character Document; the one in use carries `is_active`, and a partial unique
index allows at most one active row per user), and `player_characters.avatar_id`
(a per-character override — "account profiles for specific characters",
`ON DELETE SET NULL`). There is no `user_profiles.active_avatar_id` column.
Swapping is a `PUT`, not a re-upload. The routes sit under `/api`
(`AvatarController`, Liquibase changelog 059).

| endpoint | purpose |
|---|---|
| `GET/POST /api/avatars` | list / create the caller's avatars |
| `PUT/DELETE /api/avatars/{id}` | edit / remove |
| `POST /api/avatars/{id}/select` | make it the active avatar |
| `PUT /api/characters/{cid}/avatar` | bind an avatar to one character |
| `GET /api/avatars/{id}/image.svg?expression=&w=&h=&portrait=&mouth=` | renderable everywhere |
| `GET /api/avatars/{id}/model.glb` | glTF export of the saved avatar |

`rpg-builder` serves the same model per project under `/api/rpg`: `projects/{pid}/avatar-config` (GET/PUT), `avatar.svg`, `avatar.glb`, `avatar.json` (GET/POST), `avatar-char3d.json` and the unsaved-config `POST /avatar-preview.svg`.

The image route is the integration primitive: any service that can render an `<img>`
gets avatars for free, and the expression parameter is what produces the stills
another service attaches to a post.

## Portable Character Document v1

The interchange format — "transferable to games where there's character generation
in 2d or 3d":

```json
{ "schemaVersion": 1, "kind": "hugmesh.character", "name": "…",
  "style": "lowpoly",
  "proportions": { "height": 1.0, "head": 1.0, "chest": 1.0, "legLength": 1.0, … },
  "shape":  { "body": "slim", "head": "round", "hair": "short", "outfit": "tunic" },
  "items":  { "hat": "cap", "heldMain": "sword", "heldOff": "shield",
              "back": "cape", "face": "none" },
  "colors": { "body": "#…", "skin": "#…", "hat": "#…", "hair": "#…",
              "accent": "#…", "item": "#…", "eye": "#…" },
  "texture": { "assetId": null }, "expression": "neutral" }
```

`CharacterDocument` also carries a lossy **mapping to `Char3DConfig` v1** — the
shape `games/shadows-of-the-net/scripts/character_assembler.gd` already consumes
(`{body:{archetype,build,height}, head:{family,…}, materials:{7 regions}, style}`).
This lets the XR/Matrix 3D-chat track ingest our characters **without us editing
their files** — that tree is a vendored copy of
`hugmesh-character-forge-bff-api` and is read-only from here.

## Consequences

**Extending a whitelist now touches five places, and they are all tested** (the checklist is also in a comment at the top of `CharacterSpec.java`):
canonical `CharacterSpec` (bounds + token sets) → a new Liquibase changeset
(append-only; drop + re-add the CHECK) → `HumanoidRig` geometry → the Angular
constant → the maker UI control. `avatar_factory.gd` is *no longer* one of them:
it consumes the baked rig. Parity between 2D and 3D is no longer a review
obligation.

**Out of scope here — genuinely other repos.** The 3D chat (`shadows-of-the-net`)
is another track's vendored tree; the Matrix client and Akkoma live in
`hugmesh-matrix-ui` and `hugmesh-mastodon` entirely outside this repo. What this
decision delivers for them is the *contract*: a stable Character Document, a
`Char3DConfig` mapping, a rendered-image URL with an expression parameter, and a
glTF export. Wiring those into the Matrix and Akkoma clients is follow-on work in
those repos and is tracked as such — it is not claimed as done here.

**Existing data keeps working.** Changelog 009 adds columns with defaults; the
seven original fields keep their names, meanings and values, and the Six Realms
red-hero seed (`#e21422`) stays exactly as-is so the `@godot` centre-crop pixel
proof in `character-maker.feature` still holds.
