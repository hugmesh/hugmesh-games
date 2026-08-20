// Guard: every web-native game's stylesheet exists TWICE — the authored copy at
// games/<slug>/<slug>.css and the served copy at
// backend/src/main/resources/assets/levels/<slug>/<slug>.css — and the two MUST
// stay byte-identical.
//
// Why this exists: nothing copies these. `make build-godot` exports the Godot
// bundles; `make sync-bridge-addon` and `make sync-character-model` sync other
// trees; none of them touches a web-native game's css. The bundle files are
// hand-copied per the "keep the deployed copy byte-identical to source" rule in
// CLAUDE.md, and until now nothing enforced it. The served copy is packaged INTO
// the BFF jar as a classpath resource and handed to browsers from
// /api/assets/levels/<slug>/ (and baked into the wasm-fragment nginx image at
// image-build), so a one-sided edit ships a jar whose css silently disagrees
// with the source tree — with no failure anywhere. The per-game smokes under
// games/<slug>/test/ read the SOURCE copy, and bundle-sync.test.mjs covers only
// _shared/*.js plus the EXISTENCE (not the content) of the rest of the bundle.
// The stylesheet is the blind spot, and a bulk sed over both trees is exactly
// the change that would tear the pair apart.
//
// Shape, and why:
//  - Slug sets are compared BOTH directions, so a new game whose bundle was
//    never copied across (and an orphan bundle with no source) both fail.
//  - Within a slug the walk is RECURSIVE and the relative-path sets are compared
//    both directions, so an ADDED stylesheet at any depth is drift exactly like
//    a changed byte. (A flat listing stays green while a fork hides in a
//    subdirectory — the blindspot an adversarial pass found in the first version
//    of CharacterModelSyncTest.)
//  - A missing tree FAILS; it never skips. "Can't check" is not "fine".
//
// If a pair is ever legitimately allowed to diverge, add its "<slug>/<path>" to
// EXEMPT with a comment saying why. The list is empty today and should stay that
// way by default — an unexplained exemption defeats the whole guard.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, sep } from 'node:path';
import { createHash } from 'node:crypto';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');               // games/_shared/test → repo root
const gamesRoot = join(repo, 'games');
const levelsRoot = join(repo, 'backend', 'src', 'main', 'resources', 'assets', 'levels');

/** Pairs deliberately allowed to differ, as "<slug>/<relative-path>". */
const EXEMPT = new Set([]);

/** Build output / vendored trees that are not part of the source↔bundle contract. */
const SKIP_DIRS = new Set(['.git', '.godot', 'node_modules', 'addons', 'bin', 'target', 'dist']);

/** Floor on the pair count. A guard that quietly drops to zero pairs is vacuous. */
const MIN_PAIRS = 14;

const md5 = (p) => createHash('md5').update(readFileSync(p)).digest('hex');

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log(`  FAIL  ${m}`); } };

/** Every *.css under `dir`, at ANY depth, as sorted "/"-joined relative paths. */
function stylesheetsUnder(dir, prefix = '') {
  const out = [];
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (SKIP_DIRS.has(e.name)) continue;
      out.push(...stylesheetsUnder(join(dir, e.name), `${prefix}${e.name}/`));
    } else if (e.isFile() && e.name.endsWith('.css')) {
      out.push(`${prefix}${e.name}`);
    }
  }
  return out.sort();
}

/** slug → sorted css paths, for every immediate subdirectory of `root` that has any. */
function slugsWithStylesheets(root) {
  const map = new Map();
  for (const e of readdirSync(root, { withFileTypes: true })) {
    if (!e.isDirectory() || SKIP_DIRS.has(e.name)) continue;
    const css = stylesheetsUnder(join(root, e.name)).filter((r) => !EXEMPT.has(`${e.name}/${r}`));
    if (css.length) map.set(e.name, css);
  }
  return map;
}

const isDir = (p) => existsSync(p) && statSync(p).isDirectory();

// A missing tree means drift CANNOT be checked — a failure, not a skip.
ok(isDir(gamesRoot), `games/ source tree exists (${gamesRoot})`);
ok(isDir(levelsRoot), `served bundle tree exists (${levelsRoot})`);
if (fail) {
  console.log(`\n${pass} assertions passed, ${fail} failed.`);
  console.log('FAIL — this test needs the full repo checkout (games/ and backend/ side by side).');
  process.exit(1);
}

const source = slugsWithStylesheets(gamesRoot);
const served = slugsWithStylesheets(levelsRoot);

// Both directions on the slug sets.
for (const slug of source.keys()) {
  ok(served.has(slug),
     `${slug}: has stylesheets under games/${slug}/ but no served bundle css under `
     + `assets/levels/${slug}/ — copy the bundle files across`);
}
for (const slug of served.keys()) {
  ok(source.has(slug),
     `${slug}: served bundle carries css but games/${slug}/ has none — orphan bundle`);
}

let compared = 0;
for (const [slug, srcCss] of source) {
  const dstCss = served.get(slug);
  if (!dstCss) continue;                                  // already reported above

  // Both directions on the per-slug file lists: an added stylesheet is drift too.
  for (const rel of srcCss) {
    ok(dstCss.includes(rel), `${slug}/${rel}: missing from the served bundle`);
  }
  for (const rel of dstCss) {
    ok(srcCss.includes(rel), `${slug}/${rel}: in the served bundle but not in games/${slug}/`);
  }

  for (const rel of srcCss) {
    if (!dstCss.includes(rel)) continue;
    compared++;
    const src = join(gamesRoot, slug, ...rel.split('/'));
    const dst = join(levelsRoot, slug, ...rel.split('/'));
    ok(md5(src) === md5(dst),
       `${slug}/${rel}: served copy differs from games/${slug}/${rel} — the bundle copy is `
       + 'verbatim-only; make the edit in both, or re-copy the source file into '
       + `backend/src/main/resources/assets/levels/${slug}/`);
  }
}

ok(compared >= MIN_PAIRS,
   `checked ${compared} stylesheet pairs (expected >= ${MIN_PAIRS}) — a lower count means `
   + 'bundles vanished from one tree, not that there is nothing to guard');

console.log(`\n${compared} stylesheet pairs checked.`);
console.log(`${pass} assertions passed, ${fail} failed.`);
if (fail) {
  console.log('FAIL — a game stylesheet drifted between games/<slug>/ and the served bundle. '
    + 'The served copy is compiled into the BFF jar and shipped from '
    + '/api/assets/levels/<slug>/, so this drift reaches real browsers.');
  process.exit(1);
}
console.log('OK — every game stylesheet is byte-identical in source and served bundle.');

// Note the SKIP_DIRS choice: the recursive walk deliberately ignores generated /
// vendored subtrees (.godot import caches, per-game addons/ copies from
// `make sync-bridge-addon`, bin/, node_modules). Those are gitignored build
// output, not part of this contract, and letting them in would make the guard
// go red on a developer machine for reasons that have nothing to do with drift.
