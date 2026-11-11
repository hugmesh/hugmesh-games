// Guard: the guides do not claim that an admin sees every game.
//
// Why this exists: intro-for-coders said the home page shows "all games if
// you're admin", and the maintainer runbook used "the admin sees the games
// normally" as the tell that game_access is locking a player out
// (hugmesh-games#63 refute). Neither is true. The home page lists
// GET /api/games/available/{characterId}, which is ProgressionService
// intersected with GameAccessRepository.findVisibleGameIdsForPrincipal: a
// plain union of scope `all`, a role the caller holds, and the caller's own
// user id. No admin role is consulted. The only privileged flag on that path
// is isSuperadmin, and it widens the brand/domain filter only, never
// game_access. A games admin therefore does not see a gated game unless
// they hold its grant.
//
// So this test checks both halves. (1) The code still has no admin branch
// on that path; if one is ever added on purpose, this fails and says to
// revisit the docs. (2) The guides, howtos and published user guides do not
// state the admin bypass.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, relative, resolve } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(join(here, '..', '..', '..'));         // games/_shared/test -> repo root
const java = join(repo, 'backend', 'src', 'main', 'java', 'games', 'hugmesh', 'bff');

let pass = 0, fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log(`  FAIL  ${m}`); } };

// --- 1. the code: game_access has no admin branch ------------------------
const repoSrc = readFileSync(join(java, 'repository', 'GameAccessRepository.java'), 'utf8');
const q = /@Query\("""([\s\S]*?)"""\)\s*Flux<Long>\s+findVisibleGameIdsForPrincipal\(/.exec(repoSrc);
ok(q !== null, 'GameAccessRepository: found the @Query on findVisibleGameIdsForPrincipal');
if (q) {
  const sql = q[1].replace(/\s+/g, ' ').trim();
  const want = "SELECT DISTINCT game_id FROM game_access WHERE scope = 'all' "
    + "OR (scope = 'role' AND scope_value = ANY(:roleNames)) "
    + "OR (scope = 'user' AND scope_value = :userId)";
  ok(sql === want,
    'findVisibleGameIdsForPrincipal is no longer the plain all/role/user union. If an admin '
    + 'bypass was added, update docs/wiki/guides/intro-for-coders.md, maintainer-runbook.md '
    + `and the intro-for-coders guide.tex, then this test. Query now: ${sql}`);
  ok(!/admin/i.test(sql), 'findVisibleGameIdsForPrincipal mentions admin');
}

const progSrc = readFileSync(join(java, 'service', 'ProgressionService.java'), 'utf8');
const body = /public Flux<GameWithChaptersDto> availableGamesWithLevels\(Long characterId,\s*List<String> roleNames,\s*String userId,\s*String callerDomain,\s*boolean isSuperadmin\)\s*\{([\s\S]*?)\n    \}\n/.exec(progSrc);
ok(body !== null, 'ProgressionService: found the brand-aware availableGamesWithLevels');
if (body) {
  const uses = [...body[1].matchAll(/\bisSuperadmin\b/g)].length;
  const inDomain = [...body[1].matchAll(/Roles\.domainApplies\(\s*g\.getDomain\(\),\s*callerDomain,\s*isSuperadmin\)/g)].length;
  ok(uses === 1 && inDomain === 1,
    `availableGamesWithLevels uses isSuperadmin ${uses}x, ${inDomain}x as the domainApplies argument `
    + '(expected exactly once, only in the brand filter)');
  ok(/allowed\.contains\(g\.getId\(\)\)/.test(body[1]),
    'availableGamesWithLevels no longer filters every game through the game_access id set');
  ok(!/(?<!super)admin/i.test(body[1]), 'availableGamesWithLevels body mentions an admin role');
}

// --- 2. the docs: no admin-sees-everything claim -------------------------
const roots = [
  join(repo, 'docs', 'wiki', 'guides'),
  join(repo, 'docs', 'wiki', 'howto'),
  join(repo, 'docs', 'publishing', 'userguides'),
];
const files = [];
const walk = (d) => {
  for (const name of readdirSync(d)) {
    const p = join(d, name);
    if (statSync(p).isDirectory()) { if (name !== 'build' && name !== 'node_modules') walk(p); }
    else if (/\.(md|tex)$/.test(name)) files.push(p);
  }
};
for (const r of roots) walk(r);
ok(files.length >= 20, `scanned ${files.length} guide/howto/userguide files (expected >= 20)`);

// Phrasings of "an admin sees every game / is not filtered by game_access".
// Matched on whitespace-collapsed text so a line-wrapped .tex sentence counts.
const CLAIMS = [
  /all games if (?:you(?:'|’)re|you are) (?:an? )?(?:games )?admin/i,
  /admins? (?:sees?|can see) the games normally/i,
  /admins? (?:sees?|can see) (?:all|every) games?\b/i,
  /admins? (?:bypass(?:es)?|skips?|ignores?) (?:the )?`?game_access/i,
  // The published guide's role list (guide.tex) put it as "Sees every game in
  // the catalog regardless of \texttt{game\_access}"; `game.?_?access`
  // matches the TeX-escaped `game\_access` as well as `game_access`.
  /sees? every game in the catalog/i,
  /regardless of .{0,12}game.?_?access/i,
];
for (const f of files) {
  const text = readFileSync(f, 'utf8').replace(/\s+/g, ' ');
  for (const re of CLAIMS) {
    const m = re.exec(text);
    ok(m === null,
      `${relative(repo, f)}: claims an admin sees every game ("${m && m[0]}"); `
      + 'game_access has no admin branch (see GameAccessRepository.findVisibleGameIdsForPrincipal)');
  }
}

console.log(`docs-game-access-claims: ${pass} passed, ${fail} failed`);
process.exit(fail === 0 ? 0 : 1);
