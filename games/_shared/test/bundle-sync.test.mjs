// Guard: every served bundle's copy of the shared modules MUST be byte-identical
// to the canonical games/_shared/ source.
//
// Why this exists: the per-game smoke tests import the shared modules from
// games/_shared/ (the source), NOT from the served bundle's own _shared/ copy.
// So a stale bundle copy ships to the browser while every test stays green —
// exactly the gap that let a pre-`trailingArgs` wasm-host.js reach the realtime
// games' served bundles. This test exercises the bundle copies directly.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { createHash } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');               // games/_shared/test → repo root
const sharedSrc = join(repo, 'games', '_shared');
const levels = join(repo, 'backend', 'src', 'main', 'resources', 'assets', 'levels');

const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');
const SHARED = ['wasm-host.js', 'sse-host-client.js', 'game-frame.js'];

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log(`  FAIL  ${m}`); } };

const srcHash = Object.fromEntries(SHARED.map((m) => [m, md5(join(sharedSrc, m))]));

// Every level bundle that ships a _shared/ must match source byte-for-byte,
// and a web-native bundle must be self-contained (index.html + a wasm/ dir).
const bundles = readdirSync(levels, { withFileTypes: true })
  .filter((d) => d.isDirectory())
  .map((d) => d.name)
  .filter((name) => existsSync(join(levels, name, '_shared')));    // web-native bundles only (skip Godot cave/field/dodecahedron)

ok(bundles.length >= 14, `found ${bundles.length} web-native bundles (expected >= 14)`);

for (const b of bundles) {
  for (const m of SHARED) {
    const copy = join(levels, b, '_shared', m);
    ok(existsSync(copy), `${b}: bundle has _shared/${m}`);
    if (existsSync(copy)) {
      ok(md5(copy) === srcHash[m], `${b}: _shared/${m} byte-identical to source`);
    }
  }
  ok(existsSync(join(levels, b, 'index.html')), `${b}: bundle has index.html`);
  ok(existsSync(join(levels, b, 'wasm')), `${b}: bundle has wasm/`);
}

console.log(`\n${pass} assertions passed, ${fail} failed.`);
if (fail) { console.log('FAIL — a served bundle drifted from games/_shared/ source (re-copy games/_shared/*.js into the bundle).'); process.exit(1); }
console.log('OK — every served bundle ships the canonical shared modules.');
