# How to export and reuse a character

A character authored in the RPG maker is not welded to that project. Four renditions come off the one `CharacterSpec` — the portable **Character Document**, an **SVG** (any expression, optional portrait crop), a **`.glb`**, and a lossy **`Char3DConfig`** projection for the XR track. All four are derived from the same model ([decision 028](../decisions/028-parametric-humanoid-avatars.md)), so none of them can disagree with what the game renders.

## Prerequisites

- Casdoor `admin` or `publisher` role — every endpoint below sits under `@PreAuthorize("hasAnyRole('ADMIN','PUBLISHER')")` on [`AvatarConfigController`](../../../rpg-builder/src/main/java/games/hugmesh/rpgbuilder/controller/AvatarConfigController.java).
- The project must be **yours**: ownership is re-checked in-service per request, and someone else's project id is a `403 project owned by another user`.
- Paths are origin-relative through the host nginx (`location /api/rpg/` → the `rpg-builder` service). Examples use `http://localhost:8080`.

```bash
JWT=...                 # bearer token for an admin / publisher
PID=<project uuid>      # GET /api/rpg/projects lists yours
```

## 1. Export the portable Character Document

```bash
curl -sS -H "Authorization: Bearer $JWT" \
  http://localhost:8080/api/rpg/projects/$PID/avatar.json > hero.json
```

```json
{ "schemaVersion": 1, "kind": "hugmesh.character", "name": "",
  "style": "lowpoly",
  "proportions": { "height": 1.0, "head": 1.35, "chest": 1.2, "legLength": 0.9, … },
  "shape":  { "body": "slim", "head": "round", "hair": "ponytail", "outfit": "tunic" },
  "items":  { "hat": "cap", "heldMain": "sword", "heldOff": "shield",
              "back": "cape", "face": "none" },
  "colors": { "body": "#3f59b3", "skin": "#e6c8a0", "hat": "#202020", "hair": "#3a2a1c",
              "accent": "#d8b45a", "item": "#8a8f98", "eye": "#2b2b33" },
  "texture": { "assetId": null }, "expression": "neutral" }
```

That is the whole character: twelve proportions, the shape and item tokens, seven colours, the texture reference, the expression. `schemaVersion` is the compatibility handle. A reader ignores keys it doesn't know and defaults keys that are absent. A document whose `schemaVersion` is newer than the reader supports is rejected, not degraded (see step 2).

The document is a plain `Map` on the Java side ([`CharacterDocument`](../../../rpg-builder/src/main/java/games/hugmesh/character/CharacterDocument.java)) — no JSON library, no Spring — which is what lets the package be copied verbatim into services with different dependency sets.

## 2. Import it somewhere else

```bash
curl -sS -X POST -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' --data-binary @hero.json \
  http://localhost:8080/api/rpg/projects/$OTHER_PID/avatar.json
# → the stored RpgAvatarConfig for $OTHER_PID
```

Import **replaces** that project's hero, and it is not a way around the whitelist: a `kind` other than `hugmesh.character` is rejected, a `schemaVersion` newer than the reader supports is rejected, and any present-but-invalid value is a `400` with the offending field named — never a silent clamp. Absent fields take their column defaults.

> ⚠ **`texture.assetId` does not travel.** It names an asset of the *source* project. The import re-runs the cross-project ownership check (`skinTextureAssetId does not belong to this project`) — the same IDOR guard the asset endpoints apply. Re-upload the texture into the target project and set the new id, or drop the key.

## 3. Expression stills and portraits

```
GET /api/rpg/projects/{pid}/avatar.svg?expression=<name>&w=<px>&h=<px>&portrait=<bool>
```

| param | default | notes |
|---|---|---|
| `expression` | `neutral` | `neutral` `happy` `sad` `angry` `surprised` `wink` `sleepy` `determined` `shout`. An unknown name is a `400` from the whitelist, **not** a silently-neutral render. |
| `w` / `h` | `256` / `384` | Must be within `[16, 1024]` (`AvatarSvgRenderer.MIN_DIM`/`MAX_DIM`); outside is a `400`. |
| `portrait` | `false` | `true` crops to head and shoulders — the shape a profile picture or an attached still wants. |

The expression is render-time, not stored geometry, so one saved character produces the whole set:

```bash
for e in neutral happy angry surprised; do
  curl -sS -H "Authorization: Bearer $JWT" \
    "http://localhost:8080/api/rpg/projects/$PID/avatar.svg?expression=$e&portrait=true&w=128&h=128" \
    > "hero-$e.svg"
done
```

To render a character that is **not saved yet**, POST the config instead — same renderer, nothing persisted:

```bash
curl -sS -X POST -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"renderStyle":"lowpoly","hairStyle":"spiky","heldMain":"axe"}' \
  'http://localhost:8080/api/rpg/avatar-preview.svg?expression=determined'
```

That endpoint exists so the maker's live preview and the final art come from one renderer; it is the reason no client-side avatar drawing routine may be added.

> ⚠ These are **bearer-authenticated** endpoints, so a bare `<img src="/api/rpg/…/avatar.svg">` will not carry the token. Fetch the SVG with the token and inline it (`data:image/svg+xml;charset=utf-8,…` — what the maker does), or serve it from your own surface.

The BFF also serves saved per-user avatars at `GET /api/avatars/{id}/image.svg` (`expression`, `w`, `h`, `portrait` and a lip-sync `mouth` parameter) and `GET /api/avatars/{id}/model.glb` ([`AvatarController`](../../../backend/src/main/java/games/hugmesh/bff/controller/AvatarController.java)). Any authenticated user may read them, so another surface can embed an avatar by id. They also need the bearer token. A bare `<img>` does not carry it, and an unauthenticated request gets a `401`. The ids are unguessable 43-character tokens.

## 4. Take the mesh out — `.glb`

```bash
curl -sS -H "Authorization: Bearer $JWT" \
  http://localhost:8080/api/rpg/projects/$PID/avatar.glb -o hero.glb
```

Binary glTF 2.0, served as an attachment because it is a file to open, not something to render inline. The joint hierarchy survives the export, so what lands in Blender (or any engine that reads glTF) is an articulated character rather than a frozen picture of one — re-author the art, keep the rig. It is generated from the same `HumanoidRig` the in-game mesh and the SVG use, so the exported model and the played model are the same object.

## 5. The `Char3DConfig` bridge (XR / 3D-chat track)

```bash
curl -sS -H "Authorization: Bearer $JWT" \
  http://localhost:8080/api/rpg/projects/$PID/avatar-char3d.json
```

A separate endpoint from `avatar.json` **on purpose**: it speaks a different schema and it is lossy, so a caller should have to say which one it wants. It maps our humanoid-first model onto the creature-first `Char3DConfig` v1 shape that `character_assembler.gd` consumes — `{body:{archetype,build,height}, head:{…}, parts:{…}, materials:{7 regions}, style}`.

What survives: silhouette (`build` derived from body shape + chest, `height` passed through and clamped to their 0.5–1.5 range), head variant, hair style, hat (as `accessory`), wings (from `backItem`), all seven material colours, and `lowpoly → toon`. What is dropped: held items, back items other than wings, face items, and the per-limb proportions — their model has no counterpart for those.

We speak their format rather than changing it. `games/shadows-of-the-net/scripts/character_assembler.gd` is an independent implementation that mirrors the `Char3DConfig` v1 contract. The authority for that contract is `Char3DConfigValidator` in `hugmesh-character-forge-bff-api`. Do not edit the assembler to accommodate our model.

## ⚠ This is a contract, not an integration

The Matrix client (`hugmesh-matrix-ui`) and Akkoma (`hugmesh-mastodon`) live in **other repositories**. Nothing in this repo posts an avatar to them, and no follow-on work here will — what this repo provides is the stable surface they can build against:

- the Character Document (versioned, self-describing, no dependency on our tables),
- an image URL with an `expression` parameter (`/api/avatars/{id}/image.svg`),
- the `.glb`,
- the `Char3DConfig` projection.

Wiring those into a client is work **in that client's repo**. If you are looking for the profile-picture / post-attachment flow end to end, it does not exist yet in either direction — see the "Out of scope" section of [decision 028](../decisions/028-parametric-humanoid-avatars.md#consequences).

## ⚠ Growing a whitelist is a five-file change

Adding a hat, a hairstyle or a proportion touches `CharacterSpec` → a new Liquibase changeset → `HumanoidRig` → the mirrored Angular `const` → the maker control. The changeset goes in `rpg-builder/src/main/resources/db/changelog/`, not the BFF's changelog. The `const` is in [`rpg-maker-remote.component.ts`](../../../frontend/projects/games-rpg-maker/src/app/rpg-maker-remote.component.ts). `avatar_factory.gd` is deliberately *not* in that list; it walks the rig baked in Java. The canonical package is `rpg-builder/src/main/java/games/hugmesh/character/`. `make sync-character-model` copies it into `backend/` and `vn-builder/`. The full list is in [`CLAUDE.md`](../../../CLAUDE.md) under the maker section.

## See also

- [028 — Parametric humanoid avatars](../decisions/028-parametric-humanoid-avatars.md)
- [018 — RPG + VN makers](../decisions/018-rpg-and-vn-makers.md)
- [Publisher guide](../guides/publisher.md)
- [Add a database column](add-db-column.md) — how a changeset works (a whitelist change uses the `rpg-builder` changelog)
