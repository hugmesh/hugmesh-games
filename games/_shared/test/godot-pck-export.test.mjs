// Guard: a Godot Web export must SHIP the raw-byte files its runtime reads, and
// the tool that proves it (scripts/verify-godot-pck.mjs) must actually fail
// when they are missing.
//
// Why this exists: `export_filter="all_resources"` ships imported resources.
// character-forge's 11 `.vrm` avatars are parsed from raw bytes at runtime and
// have no `.import` sidecar, so they reach the bundle only through the preset's
// `include_filter` — and an export that drops them exits 0 with a healthy-looking
// bundle (hugmesh-character-forge-bff-api#3). Measured on Godot 4.7.1: with the
// filter blanked the PCK shrank 35.3 MB → 23.2 MB and all 11 `.vrm` were gone.
//
// Three layers, so no layer can be green vacuously:
//  1. The PCK reader is validated against REAL Godot output: every committed
//     backend/.../levels/*/index.pck parses, and every plain entry's bytes
//     re-hash to the MD5 the exporter stored. A wrong offset, a wrong header
//     size or a missed padding rule fails here, without Godot installed.
//  2. character-forge's export_required.txt covers the runtime's need: every
//     `.vrm` and `.json` under assets/packs is matched by a required glob, and
//     every required glob matches at least one real file.
//  3. The verifier's teeth, on synthetic PCKs built from the real source files:
//     a complete pack passes; a pack missing the `.vrm` entries FAILS naming
//     each of them; a pack with a drifted manifest reports it STALE; a glob that
//     matches nothing FAILS instead of passing empty; a `res://`-prefixed
//     directory (pre-v4 exporters) still verifies; and the self-check that
//     `make build-godot` runs (--self-check) reports an entry whose stored MD5
//     does not match its bytes — including one no required glob covers.
//  Plus: when a real character-forge export exists (after `make build-godot`)
//  it is verified too — and the absence is printed, never silent.
import { readFileSync, readdirSync, existsSync, writeFileSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  readPckDirectory, selfCheck, verifyPck, expandRequired, walkProject,
  parseRequiredFile, filterMatches, godotMatchN, splitFilter, PCK_MAGIC,
} from '../../../scripts/verify-godot-pck.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..');               // games/_shared/test → repo root
const levels = join(repo, 'backend', 'src', 'main', 'resources', 'assets', 'levels');
const forge = join(repo, 'games', 'character-forge');

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log(`  FAIL  ${m}`); } };
const md5 = (b) => createHash('md5').update(b).digest('hex');

// ── 0. matchn semantics mirror the engine ────────────────────────────────────
ok(godotMatchN('assets/packs/*.vrm', 'assets/packs/vroid-cc0-v1/models/base_a.vrm'), '`*` spans `/` like Godot');
ok(godotMatchN('ASSETS/PACKS/*.VRM', 'assets/packs/x/y.vrm'), 'matchn is case-insensitive');
ok(!godotMatchN('assets/packs/*.vrm', 'assets/packs/x/y.vrm.import'), 'anchored at both ends');
ok(godotMatchN('a?c', 'abc') && !godotMatchN('a?c', 'a.c'), '`?` is one char that is not `.`');
ok(filterMatches('res://assets/packs/*.json', 'assets/packs/lanes.json'), 'a res://-prefixed filter still matches');
ok(splitFilter(' assets/packs/*.json , ,assets/packs/*.vrm ').length === 2, 'filters split on `,` and trim like _edit_filter_list');

// ── 1. the reader against real Godot exports committed in this repo ──────────
const committed = readdirSync(levels, { withFileTypes: true })
  .filter((d) => d.isDirectory() && existsSync(join(levels, d.name, 'index.pck')))
  .map((d) => d.name);
ok(committed.length >= 4, `found ${committed.length} committed Godot bundles with an index.pck (expected >= 4)`);
for (const slug of committed) {
  const buf = readFileSync(join(levels, slug, 'index.pck'));
  let dir;
  try { dir = readPckDirectory(buf); } catch (e) { ok(false, `${slug}/index.pck parses: ${e.message}`); continue; }
  ok(dir.entries.length > 0, `${slug}: PCK v${dir.format} (Godot ${dir.engine}) lists ${dir.entries.length} entries`);
  ok(dir.entries.some((e) => e.path === 'project.binary'), `${slug}: directory carries project.binary (paths normalised, no res://)`);
  const bad = selfCheck(buf, dir);
  ok(bad.length === 0, `${slug}: every plain entry re-hashes to its stored MD5 (${bad.length} mismatches${bad[0] ? `: ${bad[0].path} ${bad[0].reason}` : ''})`);
}

// ── 2. export_required.txt covers what the runtime reads ────────────────────
const requiredPath = join(forge, 'export_required.txt');
ok(existsSync(requiredPath), 'games/character-forge/export_required.txt exists');
const required = existsSync(requiredPath) ? parseRequiredFile(readFileSync(requiredPath, 'utf8')) : [];
ok(required.length >= 2, `export_required.txt declares >= 2 globs (got ${required.length})`);
const files = walkProject(forge);
const expanded = expandRequired(forge, required, files);
for (const { glob, files: hit } of expanded) ok(hit.length > 0, `required glob '${glob}' matches at least one project file`);
const declared = new Set(expanded.flatMap((g) => g.files));
const vrms = files.filter((f) => f.startsWith('assets/packs/') && f.endsWith('.vrm'));
const jsons = files.filter((f) => f.startsWith('assets/packs/') && f.endsWith('.json'));
ok(vrms.length > 0 && jsons.length > 0, `the packs tree holds .vrm (${vrms.length}) and .json (${jsons.length}) runtime files`);
for (const f of [...vrms, ...jsons]) ok(declared.has(f), `export_required.txt covers ${f}`);
ok(!files.some((f) => f.endsWith('.vrm') && !declared.has(f)), 'no .vrm anywhere in the project escapes the declaration');

// The preset itself: statically, the same rule tests/run_tests.gd applies with
// the engine's own matchn — include_filter names every declared file and
// exclude_filter spares it. No export_filter mode is a substitute: measured on
// 4.7.1, all_files with a blank include_filter yields a PCK byte-identical to
// the all_resources one (EditorFileSystem does not track .vrm either).
const preset = readFileSync(join(forge, 'export_presets.cfg'), 'utf8');
const key = (k) => (preset.match(new RegExp(`^${k}="([^"]*)"`, 'm')) || [])[1];
ok(key('export_filter') === 'all_resources' || key('export_filter') === 'all_files', `Web preset export_filter is all_resources/all_files (got ${key('export_filter')})`);
const includes = splitFilter(key('include_filter'));
const excludes = splitFilter(key('exclude_filter'));
for (const f of declared) {
  const shipped = includes.some((g) => filterMatches(g, f)) && !excludes.some((g) => filterMatches(g, f));
  ok(shipped, `export_presets.cfg ships ${f} (include_filter="${key('include_filter')}", exclude_filter="${key('exclude_filter')}")`);
}
ok(excludes.some((g) => filterMatches(g, 'tests/run_tests.gd')), 'tests/ stays excluded from the bundle');

// ── 3. the verifier's teeth, on synthetic PCKs built from the real files ─────
// A minimal PCK v4 writer (the layout readPckDirectory reads; the reader itself
// was just proven against real exports above).
function buildPck(entries, { engine = [4, 7, 1] } = {}) {
  const header = Buffer.alloc(0x70);                     // 4.7.1 pads its header to 0x70
  header.writeUInt32LE(PCK_MAGIC, 0);
  header.writeUInt32LE(4, 4);
  header.writeUInt32LE(engine[0], 8); header.writeUInt32LE(engine[1], 12); header.writeUInt32LE(engine[2], 16);
  header.writeUInt32LE(2, 20);                            // PACK_REL_FILEBASE
  header.writeBigUInt64LE(BigInt(header.length), 24);      // file_base
  const blobs = [];
  const dirEntries = [];
  let ofs = 0;
  for (const { path, bytes, md5: storedMd5 } of entries) {
    const p = Buffer.from(path, 'utf8');
    const e = Buffer.alloc(4 + p.length + 8 + 8 + 16 + 4);
    let q = 0;
    e.writeUInt32LE(p.length, q); q += 4; p.copy(e, q); q += p.length;
    e.writeBigUInt64LE(BigInt(ofs), q); q += 8;
    e.writeBigUInt64LE(BigInt(bytes.length), q); q += 8;
    Buffer.from(storedMd5 ?? md5(bytes), 'hex').copy(e, q); q += 16;
    e.writeUInt32LE(0, q);
    dirEntries.push(e);
    blobs.push(bytes);
    ofs += bytes.length;
  }
  const dirOffset = header.length + ofs;
  header.writeBigUInt64LE(BigInt(dirOffset), 32);
  const count = Buffer.alloc(4); count.writeUInt32LE(entries.length, 0);
  return Buffer.concat([header, ...blobs, count, ...dirEntries]);
}
const src = (rel) => ({ path: rel, bytes: readFileSync(join(forge, rel)) });
const decoy = { path: 'scripts/pack_mount.gd.remap', bytes: Buffer.from('[remap]\npath="res://x"\n') };
const full = [...declared].map(src);

// The synthetic writer round-trips through the reader exactly.
{
  const buf = buildPck([...full, decoy]);
  const dir = readPckDirectory(buf);
  ok(dir.entries.length === full.length + 1 && dir.format === 4, 'synthetic PCK v4 round-trips through the reader');
  ok(selfCheck(buf, dir).length === 0, 'synthetic PCK passes the byte-level self-check');
}
// Complete → OK.
{
  const r = verifyPck({ pck: buildPck([...full, decoy]), projectDir: forge, required });
  ok(r.ok && r.missing.length === 0 && r.stale.length === 0, `a PCK holding every required file passes (${r.present.length} present)`);
}
// The issue's export: every .vrm dropped → FAIL, naming each one.
{
  const r = verifyPck({ pck: buildPck([...full.filter((e) => !e.path.endsWith('.vrm')), decoy]), projectDir: forge, required });
  ok(!r.ok, 'a PCK missing the .vrm files FAILS');
  ok(r.missing.length === vrms.length && vrms.every((v) => r.missing.includes(v)), `…and names all ${vrms.length} missing .vrm (got ${r.missing.length})`);
  ok(r.perGlob.find((g) => g.glob.endsWith('.json'))?.shipped === jsons.length, '…while the .json glob still reports shipped');
}
// A manifest whose bytes drifted from source → STALE, not OK.
{
  const drifted = full.map((e) => e.path === 'assets/packs/lanes.json' ? { ...e, bytes: Buffer.concat([e.bytes, Buffer.from('\n')]) } : e);
  const r = verifyPck({ pck: buildPck(drifted), projectDir: forge, required });
  ok(!r.ok && r.stale.includes('assets/packs/lanes.json') && r.missing.length === 0, 'a PCK whose lanes.json drifted from source is reported STALE');
}
// A glob that matches nothing must not pass vacuously.
{
  const r = verifyPck({ pck: buildPck(full), projectDir: forge, required: [...required, 'assets/packs/*.vrma'] });
  ok(!r.ok && r.vacuous.includes('assets/packs/*.vrma'), 'a required glob matching no project file FAILS instead of passing empty');
}
// No PCK at all is a failure of the export, not a pass.
{
  let threw = false;
  try { verifyPck({ pck: Buffer.from('not a pck'), projectDir: forge, required }); } catch { threw = true; }
  ok(threw, 'garbage input is rejected, not reported OK');
}
// A directory that stores `res://`-prefixed paths (pre-v4 exporters do) still
// verifies: the reader normalises every entry, so lookups by project path hit.
{
  const prefixed = [...full, decoy].map((e) => ({ ...e, path: 'res://' + e.path }));
  const buf = buildPck(prefixed);
  const dir = readPckDirectory(buf);
  ok(dir.entries.length === prefixed.length && dir.entries.every((e) => !e.path.startsWith('res://')), 'res:// is stripped from every directory entry');
  const r = verifyPck({ pck: buf, projectDir: forge, required });
  ok(r.ok && r.present.length === full.length, `a PCK whose directory stores res://-prefixed paths verifies (${r.present.length} present, missing ${r.missing.length})`);
}
// The self-check has teeth. Corrupt the stored MD5 of an entry NO required glob
// covers: verifyPck (which only looks at required files) still passes, so only
// selfCheck — the --self-check flag `make build-godot` passes — can catch it.
{
  const corrupt = [...full, { ...decoy, md5: '00000000000000000000000000000000' }];
  const buf = buildPck(corrupt);
  const bad = selfCheck(buf, readPckDirectory(buf));
  ok(bad.length === 1 && bad[0].path === decoy.path && /stored md5 0{32} != bytes md5/.test(bad[0].reason),
    `self-check reports exactly the entry whose stored MD5 differs from its bytes (got ${bad.length}: ${bad.map((b) => b.path).join(',')})`);
  ok(verifyPck({ pck: buf, projectDir: forge, required }).ok, '…which verifyPck alone cannot see (the entry is not required)');
  // And through the CLI, exactly as the Makefile invokes it: exit 1 only with --self-check.
  const tmp = join(tmpdir(), `verify-godot-pck-${process.pid}.pck`);
  writeFileSync(tmp, buf);
  try {
    const cli = (...extra) => spawnSync(process.execPath, [join(repo, 'scripts', 'verify-godot-pck.mjs'), '--pck', tmp, '--project', forge, '--required-file', requiredPath, ...extra], { encoding: 'utf8' });
    const plain = cli();
    ok(plain.status === 0 && /^OK — /m.test(plain.stdout), `CLI without --self-check exits 0 on it (exit ${plain.status})`);
    const self = cli('--self-check');
    ok(self.status === 1 && /CORRUPT\s+scripts\/pack_mount\.gd\.remap/.test(self.stdout) && /self-check FAILED for 1 entries/.test(self.stdout),
      `CLI --self-check exits 1 naming the corrupt entry (exit ${self.status})`);
  } finally {
    rmSync(tmp, { force: true });
  }
}
// A required entry with a corrupt stored MD5 is STALE to verifyPck and CORRUPT to selfCheck — they agree.
{
  const corrupt = full.map((e) => e.path === 'assets/packs/lanes.json' ? { ...e, md5: 'ffffffffffffffffffffffffffffffff' } : e);
  const buf = buildPck(corrupt);
  const r = verifyPck({ pck: buf, projectDir: forge, required });
  const bad = selfCheck(buf, readPckDirectory(buf));
  ok(!r.ok && r.stale.includes('assets/packs/lanes.json') && bad.length === 1 && bad[0].path === 'assets/packs/lanes.json',
    'a required entry with a corrupt stored MD5 is both STALE and CORRUPT');
}

// ── 4. the real export, when one exists ─────────────────────────────────────
const realPck = join(levels, 'character-forge', 'index.pck');
if (existsSync(realPck)) {
  const buf = readFileSync(realPck);
  const r = verifyPck({ pck: buf, projectDir: forge, required });
  ok(r.ok, `the committed character-forge export ships every required file (missing ${r.missing.length}, stale ${r.stale.length})`);
  ok(selfCheck(buf, readPckDirectory(buf)).length === 0, 'the committed character-forge export self-checks');
} else {
  console.log(`  note  no character-forge export at ${realPck} — \`make build-godot\` produces it and runs this same verifier on it`);
}

console.log(`\n${pass} assertions passed, ${fail} failed.`);
if (fail) { console.log('FAIL — the Web export guard for raw-byte runtime files is broken or the preset no longer ships them.'); process.exit(1); }
console.log('OK — the export guard has teeth and character-forge\'s preset ships every raw-byte runtime file.');
