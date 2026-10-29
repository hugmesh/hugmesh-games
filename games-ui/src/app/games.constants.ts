import { ActivatedRoute } from '@angular/router';

/**
 * Bundles that need cross-origin isolation (SharedArrayBuffer / threaded WASM:
 * COOP same-origin + COEP require-corp on the TOP-LEVEL document). The host
 * shell page sends COOP only — no COEP — so it is not crossOriginIsolated and
 * an <iframe> of these bundles cannot get a SharedArrayBuffer. They open as a
 * top-level document in a new tab instead, where the wasm-fragment tier's own
 * COOP/COEP headers make the page isolated.
 */
export const NEW_TAB_SLUGS: readonly string[] = [
  'cave',
  'chess-godot',
  'dodecahedron',
  'field',
  'shadows-of-the-net',
];

/** The one-line reason shown next to every 'Opens in a new tab' badge. */
export const NEW_TAB_REASON =
  'Needs cross-origin isolation the shell page does not provide.';

/**
 * The web-native bundles (plain JS + a small WASM core, no SharedArrayBuffer)
 * that play inside the shell in a same-origin <iframe>.
 */
export const WEB_NATIVE_SLUGS: readonly string[] = [
  'chess',
  'connect-four',
  'reversi',
  'gomoku',
  'dots-and-boxes',
  'go',
  'battleship',
  'tetris',
  'minesweeper',
  'sudoku',
  'match-three',
  'tron',
  'bomberman',
  'asteroids-duel',
];

/** Absolute same-origin path of a bundle's document, served by the
 *  wasm-fragment tier behind the fe-gateway (`Path=/_remotes/wasm-fragment/**`,
 *  StripPrefix=2). The `launch_uid` query is what the bundle's game-frame reads. */
export function bundleUrl(slug: string, launchUid?: string | null): string {
  const base = `/_remotes/wasm-fragment/${encodeURIComponent(slug)}/index.html`;
  return launchUid ? `${base}?launch_uid=${encodeURIComponent(launchUid)}` : base;
}

/** Icon the bundle ships next to its index.html (the web-native games ship
 *  icon.svg; the Godot exports ship index.icon.png). The catalog tries the
 *  catalog row's thumbnailUrl first, then this, then a letter tile. */
export function bundleIconUrl(slug: string): string {
  const safe = encodeURIComponent(slug);
  return NEW_TAB_SLUGS.includes(slug)
    ? `/_remotes/wasm-fragment/${safe}/index.icon.png`
    : `/_remotes/wasm-fragment/${safe}/icon.svg`;
}

/**
 * The route every relative link in this remote resolves against. Under the
 * host shell the injected Router is the SHELL's (root '/'), so an absolute
 * routerLink such as ['/games', 'play', slug] would miss the mount; relative
 * links against the MOUNT route (the parent of the exposed route tree's
 * component routes) resolve correctly both standalone and federated. See
 * the memory note on federated remote URL traps.
 */
export function mountRoute(route: ActivatedRoute): ActivatedRoute {
  return route.parent ?? route;
}
