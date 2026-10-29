# Asset-tier nginx for the federated wasm-fragment service.
#
# Build context is the repo root so the classpath Godot bundles in
# backend/src/main/resources/assets/levels/ can be baked in directly.
# Each game's level bundle (cave / field / dodecahedron) lives under
# /usr/share/nginx/html/<slug>/ and is served by URL path matching the
# original /api/assets/levels/<slug>/ shape — that keeps the patched
# Godot index.html's relative asset references (index.js, index.wasm,
# index.pck) resolving the same way they did when the BFF was on the
# asset path.
#
# Hot-uploaded games that land on the games_uploads volume aren't
# served from this image — that's deferred to a future iteration that
# wires DB-driven version-dir resolution in nginx (or via a sidecar).
# Today wasm-fragment serves the seed catalog (cave, field,
# dodecahedron); the BFF /api/assets/levels/* endpoint stays as the
# fallback for anything not in the seed.

FROM nginx:1.27-alpine

# Bake the classpath Godot bundles into the image. The leading "-"-
# prefixed dir under the source path is a placeholder for the umbrella
# game and ships empty; copying the parent dir keeps the trees aligned
# without per-level COPY lines that drift.
COPY backend/src/main/resources/assets/levels /usr/share/nginx/html

# Every bundle directory serves an icon.svg (the games-ui catalog tile asks
# for it first). The web-native games ship their own; a Godot export only
# writes index.icon.png, rendered from the project's icon.svg, and the
# exported directory is not in git, so a Godot game whose export was not
# built into this image (connect-four-godot, character-forge on 2026-10-29)
# had no icon at all. Copy each Godot project's own icon.svg, the source of
# its exported icon, into its bundle directory (slug = export preset name,
# see GAME_PROJECTS / build-godot in the Makefile).
COPY games/chess-godot/icon.svg                    /usr/share/nginx/html/chess-godot/icon.svg
COPY games/connect-four-godot/icon.svg             /usr/share/nginx/html/connect-four-godot/icon.svg
COPY games/character-forge/icon.svg                /usr/share/nginx/html/character-forge/icon.svg
COPY games/shadows-of-the-net/icon.svg             /usr/share/nginx/html/shadows-of-the-net/icon.svg
COPY games/dodecahedron-demo/icon.svg              /usr/share/nginx/html/dodecahedron/icon.svg
COPY games/three-wizards-in-a-trenchcoat/icon.svg  /usr/share/nginx/html/cave/icon.svg
COPY games/three-wizards-in-a-trenchcoat/icon.svg  /usr/share/nginx/html/field/icon.svg

# Shim that the host shell injects into every Godot bundle's
# index.html via the nginx sub_filter below. Captures console / error
# output and forwards it to the parent shell, plus drains incoming
# SD_INPUT_EVENT postMessages onto a window queue the GDScript bridge
# polls every frame.
COPY wasm-fragment/hugmesh-shim.js /usr/share/nginx/html/hugmesh-shim.js

COPY wasm-fragment/nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
