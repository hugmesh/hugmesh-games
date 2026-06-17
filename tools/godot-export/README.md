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

> **Version-string skew (binary vs templates).** Godot resolves templates under
> `…/export_templates/<binary-version>.stable/`. If your binary is e.g. `4.6.3`
> but only `4.6.1` templates are installed, the export fails with
> *"No export template found … 4.6.3.stable/web_*.zip"*. Either fetch the matching
> templates (`make godot-export-tools` pins a version) or symlink the installed
> ones into a matching dir:
> ```bash
> TPL=~/.local/share/godot/export_templates
> mkdir -p "$TPL/4.6.3.stable"
> ln -sf "$TPL"/4.6.1.stable/web_*.zip "$TPL/4.6.3.stable/"
> printf '4.6.3.stable\n' > "$TPL/4.6.3.stable/version.txt"
> ```

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
- **No silent stubs in prod (enforced).** When `app.build.godot-enabled=true` a real
  Web export ALWAYS emits a multi-MB `index.wasm`. The export path now fails loudly on a
  stub-shaped output rather than shipping a placeholder, in two layers:
    1. `GodotExporter.exportWeb` rejects an exit-0 export whose sibling `index.wasm` is
       empty/absent (in addition to the existing `index.html` check); and
    2. `GodotCompileWorker` re-checks the WHOLE staged `bundle/` tree just before upload
       (`assertNoStubBundles`) so no `bundle/<area|act>/index.wasm` can be empty.
  Both throw → the build is marked FAILED. Unit-tested in `GodotExporterTest`
  (`exitZeroButEmptyWasmFailsAsStub`) and `GodotCompileWorkerTest`
  (`enabledExportRefusesToShipStubWasm`) for both makers. The dev default
  (`godot-enabled=false`) still emits the zero-byte stub so the rest of the pipeline runs
  without the toolchain — the gate is unchanged; only ON is now guaranteed real.

- **Verified end-to-end (2026-06).** A rendered Flatland fixture project (RPG + VN) was
  exported on a host with Godot 4.6.3 + Web templates via the exact `GodotExporter`
  command — `godot --headless --path <godot-src> --export-release "Web" <out>/index.html` —
  producing deliverable WASM:
  `index.wasm` 1,509,558 B, `index.side.wasm` 41,113,842 B, `index.pck` ~18–20 KB,
  `snapdragon_bridge.wasm` 2,678,048 B (all `\0asm` magic-bytes verified). The maker e2e
  (`make e2e-bdd` with the overlay) additionally asserts the bundle bytes are non-empty.
