// e2e/games-arch/scenarios.mjs
//
// The scenario table the runner iterates. ONE source of truth for:
//   * which of the 14 web-native games are host-authoritative RELAY games
//     (two players, SSE relay over /api/game/stream) vs SOLO games (launch a
//     uid + serve a bundle, no relay);
//   * the EXACT representative move each relay game's frontend emits on the
//     `input` channel — copied verbatim from games/<slug>/<slug>.js so the
//     harness exercises the REAL (kind, type, payload) the deployed game
//     sends, not a synthetic stand-in. A move whose (kind, type) the BFF's
//     InputEventController Gate 1 (input_event_types lookup) rejects with 400
//     is a REAL relay bug — the harness surfaces it rather than papering over.
//
// Relay transport recap (games/_shared/sse-host-client.js + the BFF):
//   read : EventSource/GET /api/game/stream?sessionId={id}, per-channel events
//          (addEventListener('input'|'world'|...)). Both the guest's intended
//          move (kind:'control') AND the host's authoritative world echo
//          (kind:'world', type:'state') ride the SSE `input` channel — see
//          GameStateService.emitInput(): every /api/input emit fans out to the
//          session-scoped input sink regardless of kind.
//   send : POST /api/input/{characterId} body {gameSlug, sessionId, kind, type,
//          payload} (sendInput / sendWorld).
//
// 'world' below is the host echo each relay game broadcasts via sendWorld()
// (kind:'world', type:'state'); games that don't call sendWorld have world:null.

/** The 10 host-authoritative RELAY games. */
export const RELAY_GAMES = [
  {
    slug: 'chess',
    move: { kind: 'control', type: 'move', payload: { uci: 'e2e4' } },
    world: { kind: 'world', type: 'state', payload: { fen: 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1', san: 'e4', status: 'ongoing', lastMove: 'e2e4' } },
  },
  {
    slug: 'connect-four',
    move: { kind: 'control', type: 'drop', payload: { col: 3 } },
    world: null,
  },
  {
    slug: 'reversi',
    move: { kind: 'control', type: 'move', payload: { square: 19 } },
    world: null,
  },
  {
    slug: 'gomoku',
    move: { kind: 'control', type: 'move', payload: { coord: '7,7' } },
    world: null,
  },
  {
    slug: 'dots-and-boxes',
    move: { kind: 'control', type: 'claim', payload: { edge: 0 } },
    world: null,
  },
  {
    slug: 'go',
    move: { kind: 'control', type: 'move', payload: { move: 'D4' } },
    world: null,
  },
  {
    slug: 'battleship',
    move: { kind: 'control', type: 'fire', payload: { coord: 'B7' } },
    world: { kind: 'world', type: 'state', payload: { phase: 'firing', lastShot: 'B7', result: 'miss' } },
  },
  {
    slug: 'tron',
    move: { kind: 'control', type: 'dir', payload: { player: 1, dir: 'up' } },
    world: { kind: 'world', type: 'state', payload: { tick: 1, status: 'ongoing' } },
  },
  {
    slug: 'bomberman',
    move: { kind: 'control', type: 'move', payload: { player: 1, action: 'up' } },
    world: { kind: 'world', type: 'state', payload: { tick: 1, status: 'ongoing' } },
  },
  {
    slug: 'asteroids-duel',
    move: { kind: 'control', type: 'input', payload: { player: 1, thrust: true, turn: 0, fire: false } },
    world: { kind: 'world', type: 'state', payload: { tick: 1, status: 'ongoing' } },
  },
];

/** The 4 SOLO games — launch mints a uid, the bundle serves, no relay. */
export const SOLO_GAMES = ['tetris', 'minesweeper', 'sudoku', 'match-three'];

/** All 14, in catalog order. */
export const ALL_GAME_SLUGS = [...RELAY_GAMES.map((g) => g.slug), ...SOLO_GAMES];

/** Display names from federation 051 — used to assert manifest rows. */
export const EXPECTED_WASM_GAME_SLUGS = ALL_GAME_SLUGS.slice().sort();

/** Seeded characters with NULL casdoor_id (035-1 + the original Hero=1).
 *  Each session_member claims one; authorizeControl is keyed on the active
 *  member, not a static owner column, so any caller may claim any of these. */
export const CHARACTER_IDS = { player1: 1, player2: 2 };
