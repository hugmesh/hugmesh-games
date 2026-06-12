# Godot export tools (real maker builds)

The RPG/VN makers render a Godot 4 project and, when `app.build.godot-enabled=true`,
run a headless **Web** export (`GodotExporter`) to produce a real playable WASM bundle.
That needs a Godot 4 binary **and** the matching Web export templates. To keep the
default builder image small, these live on the host and are bind-mounted via
[`docker-compose.godot.yml`](../../docker-compose.godot.yml).

## Layout this dir expects

```
tools/godot-export/
  godot/godot                 # the Godot 4.6 headless binary (chmod +x), mounted at /opt/godot
  templates/<version>/        # Web export templates, mounted at
                              #   /root/.local/share/godot/export_templates/
```

`<version>` must match the editor that produced the templates, e.g. `4.6.stable`.

## Populate it

```bash
make godot-export-tools        # downloads + unpacks the pinned Godot 4.6 + Web templates here
```

(or do it by hand from https://godotengine.org/download — grab `Godot_v4.6-stable_linux.x86_64`
into `godot/godot` and the `Godot_v4.6-stable_export_templates.tpz`, unzip its `templates/`
into `templates/4.6.stable/`.)

## Run a real build

```bash
make build-with-godot          # brings rpg-builder + vn-builder up with the overlay
# then trigger a build from the maker UI (or POST /api/{rpg,vn}/projects/{id}/build)
```

## Caveats

- **Shared libs.** The `eclipse-temurin:21-jre` base is Debian-slim and may lack libs the
  Godot headless binary loads (`libgl1`, `libxi6`, `libxrandr2`, `libxcursor1`,
  `libxinerama1`, `libfontconfig1`). If `godot --version` fails inside the container, add
  them in the builder Dockerfile (an `apt-get install` line) or use a Godot image that
  bundles them.
- **Bridge binary.** Only `snapdragon_bridge.wasm` (the `web.wasm32` library) is required
  for the Web export; it's bind-mounted from `backend/src/main/resources/assets/levels/cave/`.
  Rebuild it with `make build-rust` if you change the Rust GDExtension.
- **Verify the bytes.** A successful real build produces a non-empty `index.wasm` /
  `index.pck`; the dev stub path produces zero-byte files. The maker e2e
  (`make e2e-bdd` with the overlay) asserts the bundle bytes are non-empty.
