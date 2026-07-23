# Placeholder — hugmesh-rpg-builder overwrites this at render time with the
# real asset-UUID → staged res:// path map (RpgGodotProjectRenderer.writeAssetIndex).
# Kept in the template so a generated room script's
# `preload("res://scripts/asset_index.gd")` resolves standalone (before a build
# renders the project) and so world.gd can look up any referenced asset's art.
const ASSETS := {}
