// Guard: the architecture reference (docs/wiki/architecture/0*.md) only names
// things that exist in the tree.
//
// Why this exists: the chapters cite Liquibase changesets, make targets and
// files by name, and the tree moves under them (a changeset was renamed, a
// make target gained a sibling) while the prose stays green forever, because
// nothing reads prose. This test does: every `NNN-name.xml` changeset it
// names must be in db/changelog, every `make <target>` it names must be a
// target in the root Makefile, and every relative link it carries must
// resolve to a file or directory in the repository.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(join(here, '..', '..', '..'));         // games/_shared/test → repo root
// Where the chapters live; links resolve relative to this directory.
const linkBase = join(repo, 'docs', 'wiki', 'architecture');
// Where the text is read from (DOCS_ARCH_DIR lets a copy be checked in place).
const docsDir = process.env.DOCS_ARCH_DIR ? resolve(process.env.DOCS_ARCH_DIR) : linkBase;
const changelogDir = join(repo, 'backend', 'src', 'main', 'resources', 'db', 'changelog');

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log(`  FAIL  ${m}`); } };

const chapters = readdirSync(docsDir).filter((f) => /^0\d-.*\.md$/.test(f)).sort();
ok(chapters.length === 7, `found ${chapters.length} chapters in ${docsDir} (expected 7)`);

const changesets = new Set(readdirSync(changelogDir).filter((f) => /^\d{3}-.*\.xml$/.test(f)));
ok(changesets.size >= 63, `found ${changesets.size} changesets (expected >= 63)`);

const makefile = readFileSync(join(repo, 'Makefile'), 'utf8');
const targets = new Set();
for (const line of makefile.split('\n')) {
  const m = /^([A-Za-z0-9_.-]+):(?:\s|$)/.exec(line);
  if (m) targets.add(m[1]);
  const phony = /^\.PHONY:\s*(.*)$/.exec(line);
  if (phony) for (const t of phony[1].split(/\s+/)) if (t && t !== '\\') targets.add(t);
}
ok(targets.has('build-godot') && targets.has('seed-roles'), 'Makefile target scan sees build-godot and seed-roles');

// Produced artefacts the chapters link to on purpose (git-ignored, made by make docs-*).
const PRODUCED = [/^docs\/publishing\/javadoc-output(\/|$)/, /^docs\/publishing\/.*\/build(\/|$)/];

for (const chapter of chapters) {
  const text = readFileSync(join(docsDir, chapter), 'utf8');

  // 1. Changeset names: `NNN-something.xml`
  for (const m of text.matchAll(/`(\d{3}-[a-z0-9-]+\.xml)`/g)) {
    ok(changesets.has(m[1]), `${chapter}: changeset ${m[1]} does not exist in db/changelog/`);
  }

  // 2. make targets: `make <target>` inside backticks, or a shell line that starts with make
  for (const m of text.matchAll(/(?:`|^)make ([a-z][a-z0-9-]*)/gm)) {
    ok(targets.has(m[1]), `${chapter}: make target "${m[1]}" is not in the root Makefile`);
  }

  // 3. Relative links: [text](relative/path#anchor) — not http(s), not anchor-only
  for (const m of text.matchAll(/\]\(([^)\s]+)\)/g)) {
    const raw = m[1];
    if (/^(https?:|mailto:|#)/.test(raw)) continue;
    const path = raw.replace(/#.*$/, '');
    if (path === '') continue;
    const rel = resolve(join(linkBase, path)).slice(repo.length + 1).replace(/\\/g, '/');
    if (PRODUCED.some((re) => re.test(rel))) continue;
    ok(existsSync(join(linkBase, path)), `${chapter}: link ${raw} does not resolve`);
  }
}

console.log(`docs-architecture-refs: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
