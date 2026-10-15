// Guard: a Godot game_bridge.gd that attaches the player's JWT to
// POST /api/controls/** must read it from the SAME store the host writes.
//
// The host AuthService and the federated login remote persist the session in
// localStorage under 'tw_auth' (moved off sessionStorage so open-in-new-tab
// keeps the login). A bridge that still reads only window.parent.sessionStorage
// gets an empty token, sends the control POST with no Authorization header, and
// the BFF answers 401. The bridge's fetch swallows that failure, so nothing in
// the game or the page shows it.
//
// Shape: every game_bridge.gd that reads 'tw_auth' must read
// window.parent.localStorage first; a sessionStorage read may only follow as a
// per-tab fallback. The scan must find the known bridges, so a moved or
// renamed file fails instead of passing on zero matches.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');

const bridges = [];
const gamesDir = join(repo, 'games');
for (const slug of readdirSync(gamesDir)) {
  const f = join(gamesDir, slug, 'scripts', 'game_bridge.gd');
  if (existsSync(f)) bridges.push(f);
}
const template = join(repo, 'rpg-builder', 'src', 'main', 'resources',
  'godot-template', 'scripts', 'game_bridge.gd');
if (existsSync(template)) bridges.push(template);

const MUST_EXIST = [
  'games/three-wizards-in-a-trenchcoat/scripts/game_bridge.gd',
  'rpg-builder/src/main/resources/godot-template/scripts/game_bridge.gd',
];
const failures = [];
for (const want of MUST_EXIST) {
  if (!bridges.some((f) => relative(repo, f) === want)) {
    failures.push(`${want}: not found (moved or renamed? update this guard)`);
  }
}

let readers = 0;
for (const f of bridges) {
  const rel = relative(repo, f);
  const src = readFileSync(f, 'utf8');
  if (!/getItem\(\s*'tw_auth'\s*\)/.test(src)) continue;   // bridge does not send the JWT
  readers += 1;
  const local = src.search(/window\.parent\.localStorage\.getItem\(\s*'tw_auth'\s*\)/);
  const session = src.search(/window\.parent\.sessionStorage\.getItem\(\s*'tw_auth'\s*\)/);
  if (local < 0) {
    failures.push(`${rel}: reads tw_auth but never from window.parent.localStorage`);
  } else if (session >= 0 && session < local) {
    failures.push(`${rel}: reads sessionStorage before localStorage`);
  }
}
if (readers < MUST_EXIST.length) {
  failures.push(`only ${readers} bridge(s) read tw_auth; expected at least ${MUST_EXIST.length}`);
}

if (failures.length) {
  console.error('FAIL godot-bridge-auth-storage');
  for (const m of failures) console.error('  - ' + m);
  process.exit(1);
}
console.log(`ok godot-bridge-auth-storage: ${readers} bridge(s) read tw_auth from localStorage`);
