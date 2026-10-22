// Guard: a committed Godot Web bundle must load the bridge library it ships.
//
// A Godot Web export with GDExtension support names its side-module libraries
// in two places that must agree:
//   * index.html GODOT_CONFIG.gdextensionLibs, the files the page preloads, and
//   * the .gdextension manifest packed inside index.pck, whose
//     web.release.wasm32 entry is the file the engine asks for at start-up.
// The engine opens the library by that manifest's file name. If the page
// preloaded a file under another name, the open fails ("Can't open dynamic
// library"), every Rust class (HostBridge, BridgeGlue, ChessEngine) is missing
// and the game runs as a placeholder, with no error outside the console.
//
// That is exactly what a rename without a re-export produces: renaming the
// .wasm next to index.html and editing index.html leaves the manifest inside
// index.pck pointing at the old name. Re-export with `make build-godot`.
//
// Shape: for every committed bundle under backend/.../assets/levels/ that has
// an index.pck, every .gdextension in the pack must name a web.release.wasm32
// library whose file name is listed in gdextensionLibs and exists next to
// index.html, every listed library must exist, and .godot/extension_list.cfg
// may only name manifests that are in the pack. At least the three bridge
// bundles (cave, field, chess-godot) must be checked, so a moved tree fails
// instead of passing on zero matches.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, basename } from 'node:path';
import { readPckDirectory } from '../../../scripts/verify-godot-pck.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');
const levels = join(repo, 'backend', 'src', 'main', 'resources', 'assets', 'levels');
const MUST_CHECK = ['cave', 'field', 'chess-godot'];

const failures = [];
const checked = [];

const plainEntry = (buf, e) => {
  if (e.encrypted || e.removed || e.delta) return null;
  return buf.subarray(e.offset, e.offset + e.size).toString('utf8');
};

for (const slug of readdirSync(levels)) {
  const dir = join(levels, slug);
  const pckPath = join(dir, 'index.pck');
  const htmlPath = join(dir, 'index.html');
  if (!existsSync(pckPath) || !existsSync(htmlPath)) continue;

  const html = readFileSync(htmlPath, 'utf8');
  const m = html.match(/const GODOT_CONFIG = (\{.*?\});/);
  if (!m) { failures.push(`${slug}/index.html: no GODOT_CONFIG`); continue; }
  const libs = JSON.parse(m[1]).gdextensionLibs || [];
  for (const lib of libs) {
    if (!existsSync(join(dir, lib))) failures.push(`${slug}: gdextensionLibs lists ${lib}, but no such file next to index.html`);
  }

  const buf = readFileSync(pckPath);
  const pck = readPckDirectory(buf);
  const manifests = pck.entries.filter((e) => e.path.endsWith('.gdextension'));
  if (manifests.length === 0) {
    if (libs.length > 0) failures.push(`${slug}: index.html preloads ${libs.join(', ')} but the pack has no .gdextension`);
    continue;
  }
  checked.push(slug);

  for (const e of manifests) {
    const text = plainEntry(buf, e);
    if (text === null) { failures.push(`${slug}: ${e.path} is not stored plain; cannot read it`); continue; }
    const web = text.match(/^\s*web\.release\.wasm32\s*=\s*"([^"]+)"/m);
    if (!web) { failures.push(`${slug}: ${e.path} has no web.release.wasm32 entry`); continue; }
    const want = basename(web[1]);
    if (!libs.includes(want)) {
      failures.push(`${slug}: ${e.path} loads ${want}, but index.html preloads [${libs.join(', ')}] (renamed without a re-export?)`);
    }
  }

  const list = pck.entries.find((e) => e.path === '.godot/extension_list.cfg');
  if (list) {
    const inPack = new Set(manifests.map((e) => e.path));
    for (const line of (plainEntry(buf, list) || '').split('\n')) {
      const p = line.trim().replace(/^res:\/\//, '');
      if (p && !inPack.has(p)) failures.push(`${slug}: extension_list.cfg names ${p}, which is not in the pack`);
    }
  }
}

for (const slug of MUST_CHECK) {
  if (!checked.includes(slug)) failures.push(`${slug}: expected a bridge bundle with a packed .gdextension; not found`);
}

if (failures.length) {
  console.log(`FAIL godot-bridge-lib-name: ${failures.length} problem(s)`);
  for (const f of failures) console.log(`  - ${f}`);
  process.exit(1);
}
console.log(`OK — ${checked.length} Godot bundle(s) load the bridge library they ship: ${checked.join(', ')}`);
