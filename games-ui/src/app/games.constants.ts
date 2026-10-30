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

/** Icons a bundle can ship next to its index.html, best first. Every bundle
 *  directory serves `icon.svg`: the web-native games carry their own, and the
 *  wasm-fragment image copies each Godot project's `icon.svg` (the source of
 *  its exported icon) into the bundle directory. A Godot export also writes
 *  `index.icon.png`, the fallback for an image built before that copy. The
 *  directory comes from the manifest row's remoteEntryUrl when there is one,
 *  and whether it is a Godot export from its `metadata.engine`, so a slug
 *  list cannot drift from what is served (connect-four-godot and
 *  character-forge 404'd on icon.svg when the slug list said "not Godot").
 *  The catalog tries the catalog row's thumbnailUrl first, then these, then
 *  a letter tile. */
export function bundleIconUrls(slug: string, remote?: FederationRemoteLike | null): string[] {
  const dir = bundleDir(slug, remote?.remoteEntryUrl ?? null);
  const urls = [`${dir}icon.svg`];
  if (isGodotBundle(slug, remote)) urls.push(`${dir}index.icon.png`);
  return urls;
}

/** The fields of a federation manifest row the icon lookup reads. */
export interface FederationRemoteLike {
  remoteEntryUrl: string | null;
  metadata: unknown;
}

/** `metadata` arrives as a JSON string (federation_modules.metadata) or, from
 *  a serializer that already parsed it, an object. Anything else is {}. */
export function remoteMetadata(metadata: unknown): Record<string, unknown> {
  if (metadata && typeof metadata === 'object') return metadata as Record<string, unknown>;
  if (typeof metadata === 'string' && metadata.trim()) {
    try {
      const parsed = JSON.parse(metadata);
      return parsed && typeof parsed === 'object' ? (parsed as Record<string, unknown>) : {};
    } catch {
      return {};
    }
  }
  return {};
}

/** A Godot export: the manifest row says `engine: "godot"`, or (no row
 *  metadata) the slug is one of the SAB bundles, which are all Godot. */
export function isGodotBundle(slug: string, remote?: FederationRemoteLike | null): boolean {
  if (remoteMetadata(remote?.metadata)['engine'] === 'godot') return true;
  return NEW_TAB_SLUGS.includes(slug);
}

/** `/_remotes/wasm-fragment/<slug>/` — from the manifest's remoteEntryUrl
 *  (its directory) when it is a same-origin path, else from the slug. */
function bundleDir(slug: string, remoteEntryUrl: string | null): string {
  if (remoteEntryUrl && remoteEntryUrl.startsWith('/') && !remoteEntryUrl.startsWith('//')) {
    const path = remoteEntryUrl.split(/[?#]/)[0];
    return path.slice(0, path.lastIndexOf('/') + 1);
  }
  return `/_remotes/wasm-fragment/${encodeURIComponent(slug)}/`;
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
