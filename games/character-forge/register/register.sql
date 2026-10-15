-- Character Forge — catalog self-registration for the hugmesh.games portal.
-- Runs inside the games BFF upload transaction (POST /api/admin/games/upload)
-- under RegisterSqlPolicy: only INSERTs into the catalog tables (plus the
-- games feature-flag UPDATE) are allowed, using plain single-quoted literals
-- and double-dash comments only. Mirrors the 053-chess-godot-federation
-- four-row shape (games + chapters + federation_modules of kind wasm-game +
-- game_access) but with Path-A hot-upload URLs: the bundle is served by the
-- BFF asset path /api/assets/levels/character-forge/... so no wasm-fragment
-- image rebuild is needed. Idempotent: a re-upload upserts every row.
--
-- EDITOR WARNING — DO NOT put a semicolon or a lone single quote in a comment
-- line. The vetter splits statements on semicolons found OUTSIDE single-quoted
-- literals and does NOT model double-dash comments, so a semicolon in a
-- comment ends a statement early. The tail fragment then no longer starts with
-- a comment marker, survives stripLeadingComments, fails the INSERT/UPDATE
-- allowlist, and the WHOLE upload is rejected with 400 (install transaction
-- rolled back). Pinned by RegisterSqlContractTest.

INSERT INTO games (name, description, slug, sort_order, created_at)
VALUES ('Character Forge',
        '3D character maker on a procedural parametric rig: 29 head families mapped from the VN character packs, body sliders, part attachments, 7 tint regions, and the 10 canonical expressions. Single-player studio; the saved definition drives your avatar in Shadows of the Net - CHAT.',
        'character-forge', 0, NOW())
ON CONFLICT (slug) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description;

-- chapters.scene_config is TEXT (changeset 001, never retyped) — the value
-- must stay a plain quoted literal. A jsonb cast there aborts the upload
-- transaction (no jsonb to text assignment cast). Mirrors 044/047/053.
INSERT INTO chapters (name, description, slug, scene_type, sort_order, asset_url, scene_config, game_id, created_at)
SELECT 'Forge Studio',
       'Turntable studio: pick a family, sculpt the body, attach parts, tint the seven regions, strike an expression, snapshot.',
       'character-forge',
       'character-forge',
       1,
       '/api/assets/levels/character-forge/index.html',
       '{}',
       g.id,
       NOW()
  FROM games g
 WHERE g.slug = 'character-forge'
ON CONFLICT (slug) DO UPDATE SET asset_url = EXCLUDED.asset_url, scene_type = EXCLUDED.scene_type;

INSERT INTO federation_modules (slug, display_name, kind, remote_entry_url, exposed_module, game_slug, version, enabled, metadata)
VALUES ('games-character-forge', 'Character Forge', 'wasm-game',
        '/api/assets/levels/character-forge/index.html', NULL, 'character-forge', '0.1.0', true,
        '{"sse":false,"hostAuthoritative":false,"engine":"godot","singlePlayer":true}'::jsonb)
ON CONFLICT (slug) DO UPDATE
  SET display_name = EXCLUDED.display_name,
      remote_entry_url = EXCLUDED.remote_entry_url,
      version = EXCLUDED.version,
      metadata = EXCLUDED.metadata,
      enabled = true,
      updated_at = NOW();

INSERT INTO game_access (game_id, scope, scope_value, granted_by, granted_at)
SELECT g.id, 'all', NULL, 'system:upload', NOW()
  FROM games g
 WHERE g.slug = 'character-forge'
ON CONFLICT (game_id, scope, scope_value) DO NOTHING;

UPDATE games SET features = features || '{"instant_loadout":false,"quests":false,"items":false,"char_progression":false,"audio":true,"gamepad":false,"mouse":true,"keyboard":true,"touch":true,"world":true,"control":true}'::jsonb WHERE slug = 'character-forge';
