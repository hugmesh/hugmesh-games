import { test, expect, FrameLocator, Page } from '@playwright/test';
import { SEED_USERS, login } from './helpers/roles';

/**
 * Web-native game LAUNCH matrix — the real browser render path the
 * API/SSE harness (e2e/games-arch) deliberately can't see.
 *
 * The 14 web-native games (chess + the abstract/puzzle/duel set) are
 * NOT Godot. Each is a pure-Rust engine compiled to wasm32 driving a
 * canvas frontend, served self-contained by the wasm-fragment nginx at
 * `/_remotes/wasm-fragment/<slug>/index.html`. The host shell mounts
 * them through `app-game-canvas` → `iframe.game-frame` (the
 * web-native launch decision is DATA-DRIVEN off the `wasm-game`
 * federation rows — migration 051; see frontend/.../web-native-launch.ts).
 *
 * For every one of the 14 slugs this spec:
 *   1. logs in (plain `player`),
 *   2. opens the matching game card from the grid + clicks its first
 *      unlocked chapter's Play button (the lobby/chapter modal flow),
 *   3. asserts the wasm-fragment iframe (`iframe.game-frame`) mounts,
 *   4. asserts the engine `.wasm` fetch comes down 200 (the byte the
 *      games-arch harness checks at the HTTP layer — here proven through
 *      the REAL browser iframe boot, including emscripten/streaming
 *      compile), and
 *   5. asserts a `<canvas>` element is present inside the iframe after
 *      the per-game WASM-boot signal (`window.__<game>Ready`) flips —
 *      i.e. mountGame() resolved, the engine loaded, and the frontend
 *      drew its board. This is the render layer.
 *
 * Single browser context per game (the default `page` fixture). No
 * multiplayer here — that's web-games-multiplayer.spec.ts.
 */

const PLAYER = SEED_USERS.find(u => u.username === 'player')!;

/**
 * The 14 web-native games. `slug` matches the games.slug + the
 * wasm-fragment bundle dir + the federation `wasm-game` row (051).
 * `wasmFile` is the REAL engine filename under `<slug>/wasm/` (verified
 * against each games/<slug>/index.html `wasmUrl`) so the fetch-200
 * assertion checks the exact byte the bundle requests, not a guess.
 * `bootFlag` is the per-game boot signal the bundle sets on
 * `window` once `mountGame()` resolves (the headless smoke-test hook
 * each index.html already exposes).
 */
interface WebGame {
  slug:     string;
  name:     string;
  wasmFile: string;
  bootFlag: string;
}

const WEB_GAMES: WebGame[] = [
  { slug: 'chess',          name: 'Chess',          wasmFile: 'hugmesh_chess.wasm',               bootFlag: '__chessReady'      },
  { slug: 'connect-four',   name: 'Connect Four',   wasmFile: 'hugmesh_connect_four.wasm',        bootFlag: '__connectFourReady' },
  { slug: 'reversi',        name: 'Reversi',        wasmFile: 'hugmesh_reversi_wasm.wasm',        bootFlag: '__reversiReady'    },
  { slug: 'gomoku',         name: 'Gomoku',         wasmFile: 'hugmesh_gomoku.wasm',              bootFlag: '__gomokuReady'     },
  { slug: 'dots-and-boxes', name: 'Dots and Boxes', wasmFile: 'hugmesh_dots_and_boxes.wasm',      bootFlag: '__dabReady'        },
  { slug: 'go',             name: 'Go',             wasmFile: 'hugmesh_go.wasm',                  bootFlag: '__goReady'         },
  { slug: 'battleship',     name: 'Battleship',     wasmFile: 'hugmesh_battleship_wasm.wasm',     bootFlag: '__battleshipReady' },
  { slug: 'tetris',         name: 'Tetris',         wasmFile: 'hugmesh_tetris_wasm.wasm',         bootFlag: '__tetrisReady'     },
  { slug: 'minesweeper',    name: 'Minesweeper',    wasmFile: 'hugmesh_minesweeper_wasm.wasm',    bootFlag: '__minesweeperReady' },
  { slug: 'sudoku',         name: 'Sudoku',         wasmFile: 'hugmesh_sudoku_wasm.wasm',         bootFlag: '__sudokuReady'     },
  { slug: 'match-three',    name: 'Match Three',    wasmFile: 'hugmesh_match_three_wasm.wasm',    bootFlag: '__matchThreeReady' },
  { slug: 'tron',           name: 'Tron',           wasmFile: 'hugmesh_tron_wasm.wasm',           bootFlag: '__tronReady'       },
  { slug: 'bomberman',      name: 'Bomberman',      wasmFile: 'hugmesh_bomberman_wasm.wasm',      bootFlag: '__bombermanReady'  },
  { slug: 'asteroids-duel', name: 'Asteroids',      wasmFile: 'hugmesh_asteroids_duel_wasm.wasm', bootFlag: '__asteroidsReady'  },
];

test.describe('web-native games — launch + WASM render', () => {
  for (const game of WEB_GAMES) {
    test(`${game.slug} — launches, fetches engine wasm 200, renders canvas`, async ({ page }) => {
      // Track the engine wasm fetch off the wasm-fragment proxy. The
      // bundle requests `./wasm/<engine>.wasm` relative to its
      // index.html, which resolves to
      // /_remotes/wasm-fragment/<slug>/wasm/<engine>.wasm under the
      // host proxy. A 200 here means the browser actually fetched the
      // engine the bundle asked for (not a 200-on-index.html fallthrough).
      const wasmStatuses: number[] = [];
      const wasmSuffix = `/_remotes/wasm-fragment/${game.slug}/wasm/${game.wasmFile}`;
      page.on('response', r => {
        if (r.url().includes(wasmSuffix)) wasmStatuses.push(r.status());
      });

      await login(page, PLAYER);

      // ── open this game's card from the grid ─────────────────────────
      // Each card carries a `chip--game-slug` with the exact games.slug,
      // so we filter the grid to the card whose slug chip matches —
      // robust against display-name drift / re-ordering.
      const card = page.locator('.game-card', {
        has: page.locator('.chip--game-slug', { hasText: new RegExp(`^${escapeRe(game.slug)}$`) }),
      }).first();
      await card.waitFor({ state: 'visible', timeout: 25_000 });
      await card.click();

      // ── chapter modal → first unlocked Play ─────────────────────────
      // The web games each have exactly one chapter (slug == game.slug).
      // The modal Play button is `button.play-btn` (the grid card uses a
      // `<span class="play-btn">` so we scope to the modal backdrop).
      const playBtn = page.locator('.modal-backdrop button.play-btn:not([disabled])').first();
      await playBtn.waitFor({ state: 'visible', timeout: 15_000 });
      await playBtn.click();

      // ── iframe mounts ───────────────────────────────────────────────
      // Web-native games render through the host's app-game-canvas →
      // `iframe.game-frame` (NOT the Godot remotes' `iframe.wasm-frame`).
      await page.waitForFunction(
        () => document.querySelectorAll('iframe.game-frame').length > 0,
        undefined,
        { timeout: 30_000 },
      );

      // ── engine wasm fetch is 200 ────────────────────────────────────
      // Generous window — first-load on a cold image streams + compiles
      // the wasm engine, which dominates boot.
      await expect
        .poll(() => wasmStatuses.length, {
          timeout: 90_000,
          message: `expected a fetch of ${wasmSuffix}`,
        })
        .toBeGreaterThan(0);
      expect(
        wasmStatuses,
        `every fetch of ${game.wasmFile} must be 200 (got ${wasmStatuses.join(',')})`,
      ).not.toContain(404);
      expect(wasmStatuses.some(s => s === 200), `at least one 200 for ${game.wasmFile}`).toBe(true);

      // ── canvas present after WASM boot ──────────────────────────────
      // The bundle sets `window.<bootFlag> = true` once mountGame()
      // resolves (engine loaded + first render done). We assert the boot
      // signal flipped, then that a <canvas> exists inside the iframe —
      // the actual render surface. Same-origin (wasm-fragment is proxied
      // under the host), so the iframe DOM is reachable via frameLocator.
      const frame: FrameLocator = page.frameLocator('iframe.game-frame');
      await waitForBootFlag(page, game.bootFlag, 90_000);
      await expect(
        frame.locator('canvas').first(),
        `${game.slug} must render a <canvas> in the iframe after WASM boot`,
      ).toBeVisible({ timeout: 15_000 });
    });
  }
});

/** Escape a slug for use inside a RegExp (the slug chips contain `-`). */
function escapeRe(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Poll the same-origin iframe's window for the per-game boot flag. The
 * wasm-fragment bundle runs on the host origin (proxied), so we can
 * reach the iframe's contentWindow from the host page. Falls back
 * cleanly if the frame is mid-mount (contentWindow null).
 */
async function waitForBootFlag(page: Page, flag: string, timeoutMs: number): Promise<void> {
  await page.waitForFunction(
    (f: string) => {
      const fr = document.querySelector('iframe.game-frame') as HTMLIFrameElement | null;
      const w = fr?.contentWindow as (Window & Record<string, unknown>) | null | undefined;
      if (!w) return false;
      return Boolean(w[f]);
    },
    flag,
    { timeout: timeoutMs, polling: 250 },
  );
}
