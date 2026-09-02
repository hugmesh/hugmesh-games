#!/usr/bin/env node
// verify-godot-pck.mjs — prove a Godot Web export SHIPS the raw-byte files its
// runtime reads.
//
// Why this exists: `export_filter="all_resources"` ships IMPORTED resources.
// A file the runtime opens as raw bytes — a `.vrm` parsed through godot-vrm,
// a JSON catalogue — has no `.import` sidecar, so it only reaches the PCK when
// the preset's `include_filter` names it, and an export that leaves it out
// still exits 0 with a perfectly healthy-looking bundle. That is exactly what
// hugmesh-character-forge-bff-api#3 measured: every `.vrm` gone, silently.
//
// What it checks, against the produced index.pck (no Godot needed):
//   1. every project file matched by a required glob is in the PCK directory,
//   2. byte-identical — the entry's stored MD5 equals the source file's,
//   3. every required glob matched at least one file (a typo'd glob must not
//      pass vacuously).
//
// Globs use the exporter's own semantics (EditorExportPlatform::_edit_files_with_filter):
// Godot `String.matchn` — `*` spans any run INCLUDING `/`, `?` one char that is
// not `.`, case-insensitive — tested against the path with and without `res://`.
// So `assets/packs/*.vrm` reaches `assets/packs/<pack>/models/x.vrm`.
//
// Usage:
//   node scripts/verify-godot-pck.mjs --pck <index.pck> --project <godot-project-dir> \
//        (--require <glob> ... | --required-file <games/<slug>/export_required.txt>)
//   [--self-check]  also re-hash every plain entry's bytes against its stored MD5
//                   (proves the directory was parsed correctly; used by the tests)
// Exit 0 when everything required is present and identical; 1 otherwise, with
// the missing / stale / unmatched list printed.
//
// PCK layout (core/io/file_access_pack.cpp, Godot 4.7.1 — v2, v3 and v4):
//   u32 magic "GDPC"  u32 format  u32 major  u32 minor  u32 patch  u32 flags
//   u64 file_base     (relative to the pack start for v3/v4, or v2 + REL_FILEBASE)
//   v3/v4: u64 dir_offset (+32-byte salt only for an ENCRYPTED sparse bundle)
//   v2:    16 x u32 reserved, directory follows immediately
//   directory: u32 count, then per entry
//     u32 path_len, path bytes (v2 NUL-pads to 4; v4 stores the exact length),
//     u64 offset (relative to file_base), u64 size, 16 x u8 md5, u32 flags
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { join, relative, sep, resolve } from 'node:path';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

export const PCK_MAGIC = 0x43504447;
export const PACK_DIR_ENCRYPTED = 1 << 0;
export const PACK_REL_FILEBASE = 1 << 1;
export const PACK_SPARSE_BUNDLE = 1 << 2;
export const PACK_FILE_ENCRYPTED = 1 << 0;
export const PACK_FILE_REMOVAL = 1 << 1;
export const PACK_FILE_DELTA = 1 << 2;

const md5 = (bytes) => createHash('md5').update(bytes).digest('hex');

/** Strip the `res://` prefix the exporter may or may not store (v4 stores bare paths). */
export function normalizePckPath(path) {
  let p = path.replace(/\0+$/, '');
  if (p.startsWith('res://')) p = p.slice('res://'.length);
  while (p.startsWith('./')) p = p.slice(2);
  return p;
}

/** Parse a PCK directory out of a Buffer. `start` is where the "GDPC" magic sits. */
export function readPckDirectory(buf, start = 0) {
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.byteLength);
  const u32 = (o) => dv.getUint32(o, true);
  const u64 = (o) => Number(dv.getBigUint64(o, true));
  let p = start;
  const magic = u32(p); p += 4;
  if (magic !== PCK_MAGIC) throw new Error(`not a Godot PCK (magic 0x${magic.toString(16)} at ${start})`);
  const format = u32(p); p += 4;
  const major = u32(p); p += 4;
  const minor = u32(p); p += 4;
  const patch = u32(p); p += 4;
  if (format !== 2 && format !== 3 && format !== 4) throw new Error(`unsupported PCK format version ${format}`);
  const packFlags = u32(p); p += 4;
  if (packFlags & PACK_DIR_ENCRYPTED) throw new Error('encrypted PCK directory: cannot verify without the key');
  const sparse = !!(packFlags & PACK_SPARSE_BUNDLE);
  let fileBase = u64(p); p += 8;
  if (format >= 3 || (packFlags & PACK_REL_FILEBASE)) fileBase += start;
  if (format >= 3) {
    p = u64(p) + start;          // dir_offset; the v4 salt only follows an encrypted directory
  } else {
    p += 16 * 4;                 // v2 reserved words; the directory follows
  }
  const count = u32(p); p += 4;
  const entries = [];
  for (let i = 0; i < count; i++) {
    const len = u32(p); p += 4;
    const path = normalizePckPath(buf.subarray(p, p + len).toString('utf8')); p += len;
    const offset = fileBase + u64(p); p += 8;
    const size = u64(p); p += 8;
    const digest = buf.subarray(p, p + 16).toString('hex'); p += 16;
    const flags = u32(p); p += 4;
    entries.push({
      path, offset, size, md5: digest, flags,
      encrypted: !!(flags & PACK_FILE_ENCRYPTED),
      removed: !!(flags & PACK_FILE_REMOVAL),
      delta: !!(flags & PACK_FILE_DELTA),
    });
  }
  return { format, engine: `${major}.${minor}.${patch}`, packFlags, sparse, fileBase, entries };
}

/** Re-hash every plain (unencrypted, non-delta, non-removed) entry's bytes. Returns the mismatches. */
export function selfCheck(buf, dir) {
  const bad = [];
  if (dir.sparse) return bad;                       // bytes live outside the pack
  for (const e of dir.entries) {
    if (e.removed || e.encrypted || e.delta) continue;
    if (e.offset + e.size > buf.length) { bad.push({ path: e.path, reason: 'entry runs past end of file' }); continue; }
    const got = md5(buf.subarray(e.offset, e.offset + e.size));
    if (got !== e.md5) bad.push({ path: e.path, reason: `stored md5 ${e.md5} != bytes md5 ${got}` });
  }
  return bad;
}

/** Godot String.matchn: `*` any run (including `/`), `?` one non-`.` char, case-insensitive. */
export function godotMatchN(pattern, str) {
  let re = '^';
  for (const ch of pattern) {
    if (ch === '*') re += '[\\s\\S]*';
    else if (ch === '?') re += '[^.]';
    else re += ch.replace(/[.+^${}()|[\]\\/]/g, '\\$&');
  }
  return new RegExp(re + '$', 'i').test(str);
}

/** Exactly what the exporter tests a filter against: the path with and without `res://`. */
export function filterMatches(pattern, relPath) {
  return godotMatchN(pattern, 'res://' + relPath) || godotMatchN(pattern, relPath);
}

/** Split an export preset filter the way EditorExportPlatform::_edit_filter_list does. */
export function splitFilter(filter) {
  return String(filter ?? '').split(',').map((s) => s.trim()).filter(Boolean);
}

/** Parse export_required.txt: one glob per line, `#` comments, blanks ignored. */
export function parseRequiredFile(text) {
  return text.split(/\r?\n/).map((l) => l.replace(/#.*$/, '').trim()).filter(Boolean);
}

/**
 * Walk a Godot project the way the exporter does: skip dot-directories, the
 * project data dir, `.gdignore`d trees and nested projects. Returns `/`-joined
 * paths relative to the project root.
 */
export function walkProject(projectDir) {
  const out = [];
  const walk = (dir) => {
    for (const d of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
      const full = join(dir, d.name);
      if (d.isDirectory()) {
        if (d.name.startsWith('.')) continue;
        if (existsSync(join(full, '.gdignore')) || existsSync(join(full, 'project.godot'))) continue;
        walk(full);
      } else if (d.isFile()) {
        out.push(relative(projectDir, full).split(sep).join('/'));
      }
    }
  };
  walk(projectDir);
  return out;
}

/** Expand required globs against the project tree. */
export function expandRequired(projectDir, globs, files = walkProject(projectDir)) {
  return globs.map((glob) => ({ glob, files: files.filter((rel) => filterMatches(glob, rel)) }));
}

/**
 * The check. Returns { ok, missing, stale, vacuous, present, format, engine, entryCount }.
 * `pck` may be a path or a Buffer.
 */
export function verifyPck({ pck, projectDir, required }) {
  const buf = Buffer.isBuffer(pck) ? pck : readFileSync(pck);
  const dir = readPckDirectory(buf);
  const inPck = new Map();
  for (const e of dir.entries) {
    if (e.removed) inPck.delete(e.path); else inPck.set(e.path, e);
  }
  const result = {
    ok: false, format: dir.format, engine: dir.engine, entryCount: inPck.size,
    missing: [], stale: [], vacuous: [], present: [], perGlob: [],
  };
  for (const { glob, files } of expandRequired(projectDir, required)) {
    if (files.length === 0) result.vacuous.push(glob);
    let shipped = 0;
    for (const rel of files) {
      const e = inPck.get(rel);
      if (!e) { result.missing.push(rel); continue; }
      const disk = md5(readFileSync(join(projectDir, rel)));
      if (e.md5 !== disk) { result.stale.push(rel); continue; }
      result.present.push(rel);
      shipped++;
    }
    result.perGlob.push({ glob, wanted: files.length, shipped });
  }
  result.ok = result.missing.length === 0 && result.stale.length === 0 && result.vacuous.length === 0;
  return result;
}

export function formatReport(pckLabel, r) {
  const lines = [`verify-godot-pck: ${pckLabel} — Godot ${r.engine} export, PCK v${r.format}, ${r.entryCount} entries`];
  for (const g of r.perGlob) {
    const tag = g.wanted === 0 ? 'NONE ' : (g.shipped === g.wanted ? 'ok   ' : 'FAIL ');
    lines.push(`  ${tag} ${g.glob}  ${g.shipped}/${g.wanted} files present and byte-identical`);
  }
  for (const m of r.missing) lines.push(`  MISSING  ${m}`);
  for (const s of r.stale) lines.push(`  STALE    ${s}  (PCK bytes differ from the source file — re-export)`);
  for (const v of r.vacuous) lines.push(`  UNMATCHED glob '${v}' matches no project file — fix the glob or drop it`);
  if (r.ok) {
    lines.push(`OK — ${r.present.length} required files are in the PCK, byte-identical.`);
  } else {
    lines.push(`FAIL — missing ${r.missing.length}, stale ${r.stale.length}, unmatched globs ${r.vacuous.length}.`);
    lines.push('  With export_filter="all_resources" a raw-byte file ships ONLY when the preset\'s');
    lines.push('  include_filter names it (and exclude_filter does not). Fix export_presets.cfg and re-export.');
  }
  return lines.join('\n');
}

function usage(msg) {
  if (msg) console.error(`verify-godot-pck: ${msg}`);
  console.error('usage: node scripts/verify-godot-pck.mjs --pck <index.pck> --project <dir> (--require <glob> ... | --required-file <file>) [--self-check]');
  process.exit(2);
}

function main(argv) {
  let pck, project, requiredFile, self = false;
  const required = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--pck') pck = argv[++i];
    else if (a === '--project') project = argv[++i];
    else if (a === '--require') required.push(argv[++i]);
    else if (a === '--required-file') requiredFile = argv[++i];
    else if (a === '--self-check') self = true;
    else usage(`unknown argument ${a}`);
  }
  if (!pck || !project) usage('--pck and --project are required');
  if (requiredFile) required.push(...parseRequiredFile(readFileSync(requiredFile, 'utf8')));
  if (required.length === 0) usage('nothing to require: pass --require or --required-file');
  if (!existsSync(pck) || !statSync(pck).isFile()) { console.error(`verify-godot-pck: FAIL — no PCK at ${pck}`); process.exit(1); }
  const buf = readFileSync(pck);
  const r = verifyPck({ pck: buf, projectDir: resolve(project), required });
  console.log(formatReport(pck, r));
  if (self) {
    const bad = selfCheck(buf, readPckDirectory(buf));
    for (const b of bad) console.log(`  CORRUPT  ${b.path}: ${b.reason}`);
    console.log(bad.length ? `self-check FAILED for ${bad.length} entries` : 'self-check OK — every plain entry re-hashes to its stored MD5');
    if (bad.length) process.exit(1);
  }
  process.exit(r.ok ? 0 : 1);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main(process.argv.slice(2));
}
