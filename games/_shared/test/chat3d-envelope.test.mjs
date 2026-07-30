// Chat3D envelope shim — functional test of the JS that
// games/shadows-of-the-net/scripts/chat3d_bridge.gd injects into the room's
// iframe with JavaScriptBridge.eval().
//
// Why a node test and not a GDScript one: that JS is a browser-only code path.
// A headless Godot run never executes it, so every defect in it ships green.
// This test EXTRACTS the real template out of the .gd file (including the
// SIGNATURES / ALIASES / MAX_MEDIA_BYTES tables the GDScript side injects) and
// runs it, so the two can't drift.
//
// It pins the three cross-repo contract defects the XR-chat audit found:
//
//  * `ready` handshake — snapdragon-matrix-ui's Chat3dHostComponent queues
//    EVERY outbound envelope until the game announces `ready` and keeps
//    Enter-VR inert. The emitted envelope must be exactly what matrix-ui's
//    parseGameMessage() accepts: {v:1, t:'ready'}. The games shim's own
//    SD_SHIM_READY carries no `v` and is dropped by matrix-ui.
//
//  * named-field decoding — a host that sends named fields instead of an
//    `args` array must still decode. The canonical §5 names always win; the
//    older TypeScript spellings (users/config/value/values/speaking/on/
//    meta/bytes) are accepted as aliases so an un-upgraded host degrades
//    instead of silently nulling seven of twelve channels.
//
//  * binary transport — the inbound queue is drained through JSON.stringify
//    before GDScript sees it, and JSON.stringify flattens an ArrayBuffer to
//    `{}`. Any ArrayBuffer / TypedArray argument must therefore be base64'd at
//    PUSH time, which is exactly the `pngBase64` shape Marshalls.base64_to_raw
//    expects on the GDScript side.
//
// Run: node games/_shared/test/chat3d-envelope.test.mjs
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const repo = join(here, '..', '..', '..'); // games/_shared/test -> repo root
const BRIDGE_GD = join(repo, 'games', 'shadows-of-the-net', 'scripts', 'chat3d_bridge.gd');

let pass = 0;
let fail = 0;
const ok = (c, m) => { if (c) { pass++; } else { fail++; console.log(`  FAIL  ${m}`); } };
const eq = (a, b, m) => ok(JSON.stringify(a) === JSON.stringify(b),
  `${m} (expected ${JSON.stringify(b)}, got ${JSON.stringify(a)})`);

// ── extract the real shim + its injected tables out of the GDScript ──────────

const gd = readFileSync(BRIDGE_GD, 'utf8');

// A missing table is reported as a FAILING ASSERTION rather than a crash, so a
// regression that deletes one produces a readable diff instead of a stack trace.
/** A GDScript dictionary literal is JSON once trailing commas are gone. */
function gdDict(name, fallback) {
  const m = gd.match(new RegExp(`const ${name} := (\\{[\\s\\S]*?\\n\\})`));
  ok(!!m, `chat3d_bridge.gd declares const ${name}`);
  if (!m) return fallback;
  return JSON.parse(m[1].replace(/,(\s*[}\]])/g, '$1'));
}

function gdInt(name, fallback) {
  const m = gd.match(new RegExp(`const ${name} := ([0-9*+ ]+)`));
  ok(!!m, `chat3d_bridge.gd declares const ${name}`);
  if (!m) return fallback;
  const expr = m[1].trim();
  if (!/^[0-9*+ ]+$/.test(expr)) throw new Error(`unexpected ${name} expression: ${expr}`);
  return expr.split('+').reduce((s, term) =>
    s + term.split('*').reduce((p, f) => p * Number(f.trim()), 1), 0);
}

const SIGNATURES = gdDict('SIGNATURES', {});
const ALIASES = gdDict('ALIASES', {});
const MAX_MEDIA_BYTES = gdInt('MAX_MEDIA_BYTES', 4 * 1024 * 1024);

const tpl = gd.match(/JavaScriptBridge\.eval\("""\n([\s\S]*?)\n\t"""/);
if (!tpl) throw new Error('could not extract the injected JS template');
// The GDScript "%" format substitutes [sig_json, alias_json, MAX_MEDIA_BYTES].
const substitutions = [JSON.stringify(SIGNATURES), JSON.stringify(ALIASES), String(MAX_MEDIA_BYTES)];
let i = 0;
const shimSource = tpl[1].replace(/%[sd]/g, () => substitutions[i++] ?? 'null');
ok(i === 3, `shim template consumes all 3 injected values (consumed ${i})`);

// ── a minimal browser surface ───────────────────────────────────────────────

const HOST = 'https://matrix.hugmesh.systems';

function bootShim({ search = `?mode=matrix&origin=${encodeURIComponent(HOST)}`, referrer = '' } = {}) {
  const listeners = [];
  const posted = [];
  const window = {
    location: { search },
    addEventListener(type, fn) { if (type === 'message') listeners.push(fn); },
    parent: { postMessage(msg, origin) { posted.push({ msg, origin }); } },
  };
  const document = { referrer };
  // eslint-disable-next-line no-new-func
  new Function('window', 'document', shimSource)(window, document);
  return {
    api: window.Chat3D,
    posted,
    deliver: (data, origin = HOST) => listeners.forEach((fn) => fn({ origin, data })),
    drain: () => JSON.parse(window.Chat3D._drain()),
  };
}

// ── 1. the `ready` handshake matrix-ui gates all outbound traffic on ─────────

{
  const { api, posted } = bootShim();
  ok(!!api, 'the shim installs window.Chat3D');
  api._emit('ready', [], {});
  eq(posted.length, 1, 'ready is posted to the parent');
  eq(posted[0].msg, { v: 1, t: 'ready', args: [] }, 'ready envelope is exactly {v:1,t:"ready",args:[]}');
  ok(posted[0].origin === HOST, 'ready is posted to the allowed origin, never "*"');
  ok(posted[0].msg.v === 1, 'ready carries the protocol version matrix-ui requires');

  // The other three game->host types matrix-ui declares.
  api._emit('localPose', [1, 2, 3, -1], { x: 1, z: 2, yaw: 3, seat: -1 });
  api._emit('mediaActivate', ['table-1'], { surfaceId: 'table-1' });
  api._emit('vrState', [true], { active: true });
  eq(posted.slice(1).map((p) => p.msg.t), ['localPose', 'mediaActivate', 'vrState'],
    'all four game->host types post');
  eq(posted[1].msg.x, 1, 'outbound envelopes carry BOTH args[] and named fields');
  eq(posted[1].msg.args, [1, 2, 3, -1], 'outbound args stay in signature order');
  ok(posted.every((p) => p.origin === HOST), 'no outbound message ever targets "*"');
}

// ── 2. no allowed origin => postMessage I/O disabled (honest degradation) ────

{
  const { api, posted, deliver, drain } = bootShim({ search: '?mode=matrix', referrer: '' });
  api._emit('ready', [], {});
  eq(posted.length, 0, 'with no ?origin= and no referrer, nothing is posted (no "*" hole)');
  deliver({ v: 1, t: 'setLocal', userId: '@me:x' });
  eq(drain().length, 0, 'and no inbound message is accepted either');
}

// ── 3. origin allow-list on inbound ─────────────────────────────────────────

{
  const { deliver, drain } = bootShim();
  deliver({ v: 1, t: 'setLocal', args: ['@me:x'] }, 'https://evil.example.com');
  eq(drain().length, 0, 'a message from a foreign origin is dropped');
  deliver({ v: 1, t: 'setLocal', args: ['@me:x'] }, `${HOST}.evil.tld`);
  eq(drain().length, 0, 'a suffix-extended origin is dropped (exact match only)');
  deliver({ v: 2, t: 'setLocal', args: ['@me:x'] });
  eq(drain().length, 0, 'a wrong protocol version is dropped');
  deliver({ v: 1, t: 'notARealCall', args: [] });
  eq(drain().length, 0, 'an unknown call name is dropped');
  deliver({ v: 1, t: 'setLocal', args: ['@me:x'] });
  eq(drain(), [{ t: 'setLocal', args: ['@me:x'] }], 'a well-formed message is accepted');
}

// ── 4. the canonical args[] path (what matrix-ui sends today) ───────────────

{
  const { deliver, drain } = bootShim();
  const roster = JSON.stringify([{ userId: '@a:t', name: 'A' }]);
  deliver({ v: 1, t: 'roster', args: [roster], json: roster });
  eq(drain(), [{ t: 'roster', args: [roster] }], 'an args[] envelope decodes verbatim');
}

// ── 5. named-field fallback: canonical names, then the documented aliases ────

{
  const { deliver, drain } = bootShim();
  // Canonical §5 names, no args array.
  deliver({ v: 1, t: 'openness', userId: '@a:t', f: 0.5 });
  eq(drain(), [{ t: 'openness', args: ['@a:t', 0.5] }], 'canonical signature names decode');

  // The older TypeScript spellings every one of these channels used to ship.
  const legacy = [
    [{ v: 1, t: 'roster', users: '[]' }, ['[]']],
    [{ v: 1, t: 'avatar', userId: '@a:t', config: '{"style":"toon"}' }, ['@a:t', '{"style":"toon"}']],
    [{ v: 1, t: 'openness', userId: '@a:t', value: 0.25 }, ['@a:t', 0.25]],
    [{ v: 1, t: 'blendshapes', userId: '@a:t', values: 'AAA=' }, ['@a:t', 'AAA=']],
    [{ v: 1, t: 'speaking', userId: '@a:t', speaking: true }, ['@a:t', true]],
    [{ v: 1, t: 'stage', userId: '@a:t', on: true }, ['@a:t', true]],
    [{ v: 1, t: 'placeMedia', surfaceId: 'table-1', meta: '{"label":"x"}', bytes: null },
      ['table-1', '{"label":"x"}', null]],
  ];
  for (const [envelope, expected] of legacy) {
    deliver(envelope);
    eq(drain(), [{ t: envelope.t, args: expected }],
      `alias-named ${envelope.t} decodes to §5 argument order`);
  }

  // A canonical name present alongside an alias must WIN.
  deliver({ v: 1, t: 'speaking', userId: '@a:t', value: false, speaking: true });
  eq(drain(), [{ t: 'speaking', args: ['@a:t', false] }], 'canonical name beats the alias');

  // An unknown field name still decodes to null rather than to another slot.
  deliver({ v: 1, t: 'speaking', userId: '@a:t', talking: true });
  eq(drain(), [{ t: 'speaking', args: ['@a:t', null] }], 'an unrecognised field stays null');
}

// ── 6. every alias belongs to a real slot of its own call ───────────────────

for (const [call, slots] of Object.entries(ALIASES)) {
  ok(Array.isArray(SIGNATURES[call]), `alias table entry ${call} names a real call`);
  for (const slot of Object.keys(slots)) {
    ok((SIGNATURES[call] || []).includes(slot),
      `${call} alias targets its own signature slot "${slot}"`);
  }
}

// ── 7. binary transport: ArrayBuffer / TypedArray -> base64 at push time ────

{
  const { deliver, drain } = bootShim();
  const png = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0xff]);
  const expectedB64 = Buffer.from(png).toString('base64');

  deliver({ v: 1, t: 'placeMedia', surfaceId: 'table-1', meta: '{}', bytes: png.buffer });
  const [structured] = drain();
  eq(structured.args[2], expectedB64, 'a structured-clone ArrayBuffer arrives as base64');
  ok(typeof structured.args[2] === 'string'
    && Buffer.from(structured.args[2], 'base64').equals(Buffer.from(png)),
    'the base64 round-trips back to the original PNG bytes');

  deliver({ v: 1, t: 'placeMedia', surfaceId: 'table-1', meta: '{}', bytes: png });
  eq(drain()[0].args[2], expectedB64, 'a Uint8Array view is base64d too');

  // A view with a non-zero byteOffset must encode only its own window.
  const backing = new Uint8Array([0xaa, 0xbb, ...png]);
  const view = new Uint8Array(backing.buffer, 2, png.length);
  deliver({ v: 1, t: 'placeMedia', surfaceId: 'table-1', meta: '{}', bytes: view });
  eq(drain()[0].args[2], expectedB64, 'an offset view encodes only its own bytes');

  // Anything binary reaching JSON.stringify unencoded serialises to {} — the
  // exact silent-loss failure this normalisation exists to prevent.
  eq(JSON.stringify({ b: png.buffer }), '{"b":{}}',
    'premise: an un-normalised ArrayBuffer would JSON.stringify to {}');

  // A base64 string from an already-upgraded host passes through untouched.
  deliver({ v: 1, t: 'placeMedia', args: ['table-1', '{}', expectedB64] });
  eq(drain()[0].args[2], expectedB64, 'a base64 string host is unaffected');
}

// ── 8. oversized media degrades to a meta-only plaque, never throws ─────────

{
  const { deliver, drain } = bootShim();
  const huge = new Uint8Array(MAX_MEDIA_BYTES + 1);
  deliver({ v: 1, t: 'placeMedia', surfaceId: 'table-1', meta: '{"label":"big"}', bytes: huge });
  const [call] = drain();
  eq(call.t, 'placeMedia', 'an oversized texture still delivers the placement');
  eq(call.args[2], null, 'oversized bytes are dropped, not encoded');
  eq(call.args[1], '{"label":"big"}', 'the metadata survives so the plaque still labels');

  const atLimit = new Uint8Array(MAX_MEDIA_BYTES);
  deliver({ v: 1, t: 'placeMedia', surfaceId: 'table-1', meta: '{}', bytes: atLimit });
  ok(typeof drain()[0].args[2] === 'string', 'a texture exactly at the limit is accepted');
}

// ── 9. the inbound queue is bounded ─────────────────────────────────────────

{
  const { deliver, drain } = bootShim();
  for (let n = 0; n < 2100; n++) {
    deliver({ v: 1, t: 'setLocal', args: [`@u${n}:t`] });
  }
  ok(drain().length <= 2049, 'a runaway producer cannot grow the queue without bound');
}

console.log(`\n${pass} assertions passed, ${fail} failed.`);
if (fail) {
  console.log('FAIL — the injected Chat3D shim drifted from the §5 envelope contract.');
  process.exit(1);
}
console.log('OK — Chat3D envelope shim matches the DESIGN §5 contract.');
