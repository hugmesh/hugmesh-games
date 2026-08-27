# Third-party assets — godot/character-forge

Aggregated attributions for every third-party file vendored into this Godot
project. Machine-readable per-tree provenance (licenseSpdx / attribution /
sourceUrl / retrievedAt) lives in `addons/THIRD_PARTY_MANIFEST.json`; keep the
two files in lockstep when vendoring anything new.

License discipline (binding, from the XR-chat research verdicts): only
CC0 / CC-BY / MIT items with a positive redistribution verdict in
`legal-inventory.md` / `kemono.md` / `jrpg-bases.md` / `link-audit.md` may be
vendored. NOTHING from Mixamo, Synty, VRoid Hub, BOOTH, CGTrader, or
unverified Sketchfab uploads. VRoid meshes never enter the part-mixer
(pixiv guideline clause). User-supplied packs stay uncommitted.

## Vendored trees

### godot-vrm (`addons/vrm/`)
- License: MIT (LICENSE kept in `addons/vrm/LICENSE`)
- Copyright: (c) 2020-2021 V-Sekai Contributors; (c) 2020 VRM Consortium
- Source: https://github.com/V-Sekai/godot-vrm
  (master @ `e15199f980064028bfa4fbee5e70dddb82dd55c3`, retrieved 2026-07-23)
- Scope: the `addons/vrm` subtree ONLY. The upstream `vrm_samples/` models
  (e.g. Alicia Solid) are explicitly NOT vendored — they carry per-model
  non-CC licenses.
- Use: VRM 0.x/1.0 ingest via `GLTFDocumentExtension` runtime registration
  (`scripts/vrm_ingest.gd`). On web exports the house toon shader
  (`shaders/house_toon.gdshader`) is swapped in place of MToon during the
  mandatory GLB/VRM fixup pass.

### Godot MToon Shader (`addons/Godot-MToon-Shader/`)
- License: MIT (LICENSE kept in `addons/Godot-MToon-Shader/LICENSE`)
- Copyright: (c) 2020-2022 V-Sekai Contributors; (c) 2018 Masataka SUMI
- Source: https://github.com/V-Sekai/godot-vrm (same tarball/commit as above)
- Use: satisfies `addons/vrm/vrm_extension.gd`'s MToon material resolution.
  Desktop-preview only; never relied on for the WebGL2/Compatibility path
  (undocumented upstream — technique.md gap).

## Vendored MODELS live one level up

This file covers vendored *code* only (the two addon trees above). The 38
CC0/CC-BY character models under `assets/packs/` each carry their own
`pack.json` provenance record (licenseSpdx / attribution / sourceUrl /
retrievedAt plus the measured triangle/bone/viseme audit), and the repo-root
[`../../THIRD_PARTY_ASSETS.md`](../../THIRD_PARTY_ASSETS.md) aggregates every
one of them. Do not duplicate those entries here — that file is generated from
`tools/asset-pipeline/sources.json` and would drift.

The load path is unchanged by their arrival: models still reach a rig only
through the family/archetype basis registry (`scripts/part_registry.gd`), which
REFUSES an entry lacking complete provenance, and the runtime loader
(`scripts/glb_loader.gd`) / VRM ingest still own the fixup pass.

## Not vendored (deliberately)

- VRM sample models (Alicia Solid: DWANGO terms; AvatarSample_A/B/C:
  conditions-of-use) — see legal-inventory.md.
- Any true-kemono base: none exists under CC0/CC-BY. The good ones
  (BOOTH VN3 derivatives, TheRPGslayer's free trio) forbid redistribution even
  when they are free, so they are LINKED from the importer's "where to get one"
  list and can only ever reach the app through a user's own import.
- Blender-only sources (the OGA 'Base Rigged Stylized Humanoid (YW)', DaemonBase,
  the Blender Studio rigs): no glTF/FBX export exists in the archive and Blender
  is not available to this pipeline — recorded as a blocked capability in
  `assets/packs/lanes.json`, not silently skipped.
