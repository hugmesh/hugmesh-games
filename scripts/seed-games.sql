-- Upsert level records with their per-level Godot WASM asset URLs.
-- Safe to run multiple times (ON CONFLICT … DO UPDATE).
-- Run via: make seed-db
--      or: psql -h localhost -U threewizards -d threewizards -f scripts/seed-games.sql
--
-- After migration 012 the `games` table is the umbrella tier and `levels`
-- holds the per-WASM rows with asset_url + scene_type. Updates here target
-- `levels` accordingly.

-- Snapdragon Games is a portal: each row in `games` is a separate game
-- title. "Three Wizards In A Trenchcoat" is just one of the games in the
-- catalog; the new Dodecahedron Demo is a second, standalone game with
-- no quests or items (its level is a pure visual demo).
INSERT INTO games (id, name, description, slug, sort_order, created_at)
VALUES (1, 'Three Wizards In A Trenchcoat',
        'A JRPG romp across cave depths and open fields. Three wizards, one trenchcoat.',
        'three-wizards', 1, NOW())
ON CONFLICT (slug) DO UPDATE
    SET name        = EXCLUDED.name,
        description = EXCLUDED.description,
        sort_order  = EXCLUDED.sort_order;

INSERT INTO games (id, name, description, slug, sort_order, created_at)
VALUES (2, 'Dodecahedron Demo',
        'A spinning regular dodecahedron rendered in WebGL. Pure visual demo — no objectives, no inventory, no controls.',
        'dodecahedron', 2, NOW())
ON CONFLICT (slug) DO UPDATE
    SET name        = EXCLUDED.name,
        description = EXCLUDED.description,
        sort_order  = EXCLUDED.sort_order;

-- Levels — point each at its own per-level WASM bundle. The dodecahedron
-- level uses scene_type = 'dodecahedron' which the home view treats as a
-- chrome-less demo (no HUD, no controls overlay).
INSERT INTO levels
    (name, description, slug, scene_type, sort_order, asset_url, scene_config, game_id, created_at)
VALUES
    (
        'The Enchanted Cave',
        'Explore a mystical underground cavern filled with floating fairies and ancient magic.',
        'cave', 'cave', 1,
        '/api/assets/levels/cave/index.html',
        '{"scene":"cave","ambientColor":[0.55,0.65,0.85],"ambientEnergy":1.8,"fogDensity":0.003}',
        1, NOW()
    ),
    (
        'Open Field',
        'A peaceful meadow stretching to the horizon, with a weathered stone cottage, scattered oaks, and warm afternoon light.',
        'field', 'field', 2,
        '/api/assets/levels/field/index.html',
        '{}',
        1, NOW()
    ),
    (
        'Spinning Dodecahedron',
        'A regular dodecahedron rotating in 3D space. Take a moment, watch it spin.',
        'dodecahedron', 'dodecahedron', 1,
        '/api/assets/levels/dodecahedron/index.html',
        '{}',
        2, NOW()
    )
ON CONFLICT (slug) DO UPDATE
    SET asset_url   = EXCLUDED.asset_url,
        name        = EXCLUDED.name,
        description = EXCLUDED.description,
        scene_type  = EXCLUDED.scene_type,
        sort_order  = EXCLUDED.sort_order,
        game_id     = EXCLUDED.game_id;
