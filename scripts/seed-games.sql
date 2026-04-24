-- Upsert game records with their Godot WASM asset URLs.
-- Safe to run multiple times (ON CONFLICT … DO UPDATE).
-- Run via: make seed-db
--      or: psql -h localhost -U threewizards -d threewizards -f scripts/seed-games.sql

INSERT INTO games
    (name, description, slug, scene_type, sort_order, thumbnail_url, asset_url, scene_config, created_at)
VALUES
    (
        'The Enchanted Cave',
        'Explore a mystical underground cavern filled with floating fairies and ancient magic.',
        'cave', 'cave', 1, NULL,
        '/api/assets/games/cave/index.html',
        '{"scene":"cave","ambientColor":[0.55,0.65,0.85],"ambientEnergy":1.8,"fogDensity":0.003}',
        NOW()
    ),
    (
        'Open Field',
        'A peaceful meadow stretching to the horizon, with a weathered stone cottage, scattered oaks, and warm afternoon light.',
        'field', 'field', 2, NULL,
        '/api/assets/games/cave/index.html',
        '{}',
        NOW()
    )
ON CONFLICT (slug) DO UPDATE
    SET asset_url   = EXCLUDED.asset_url,
        name        = EXCLUDED.name,
        description = EXCLUDED.description,
        scene_type  = EXCLUDED.scene_type,
        sort_order  = EXCLUDED.sort_order;
