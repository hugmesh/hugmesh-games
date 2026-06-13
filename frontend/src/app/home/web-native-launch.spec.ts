import {
  isWebNativeChapter, resolveWebNativeRemote, webNativeBaseUrl, webNativeSlug,
} from './web-native-launch';
import { AvailableGame } from '../models/available-game.model';
import { FederationRemote } from '../services/federation.service';

// Pure unit tests for the data-driven web-native launch decision. No
// browser/DOM needed — these functions take the federation map + a
// chapter and return the launch routing. Karma still runs them (they're
// `.spec.ts`), but they'd pass equally under a plain node runner because
// nothing here touches Angular. See web-native-launch.ts for why the
// decision is extracted: it's the launchability seam that used to be a
// hardcoded slug list and is now manifest-driven.
describe('web-native-launch (pure launch decision)', () => {

  function fedRow(gameSlug: string): FederationRemote {
    return {
      slug: `games-${gameSlug}`,
      displayName: gameSlug,
      kind: 'wasm-game',
      remoteEntryUrl: `/_remotes/wasm-fragment/${gameSlug}/index.html`,
      exposedModule: null,
      gameSlug,
      version: '0.1.0',
      metadata: '{"sse":true,"hostAuthoritative":true}',
    };
  }

  function chapter(partial: Partial<AvailableGame>): AvailableGame {
    return {
      id: 1, gameId: 1, name: 'X', description: '', slug: 'chess',
      sceneType: 'chess', assetUrl: null, thumbnailUrl: null, sortOrder: 1,
      unlocked: true, requiredQuestIds: [], completedQuestIds: [],
      totalQuests: 0, completedQuests: 0, ...partial,
    };
  }

  const manifest = new Map<string, FederationRemote>([
    ['chess', fedRow('chess')],
    ['connect-four', fedRow('connect-four')],
    ['asteroids-duel', fedRow('asteroids-duel')],
  ]);

  it('recognises a chapter whose game has a wasm-game row as web-native', () => {
    expect(isWebNativeChapter(chapter({ slug: 'chess', sceneType: 'chess' }), manifest)).toBe(true);
    expect(isWebNativeChapter(chapter({ slug: 'connect-four', sceneType: 'connect-four' }), manifest)).toBe(true);
  });

  it('resolves the game slug + base URL from the manifest row', () => {
    const c = chapter({ slug: 'asteroids-duel', sceneType: 'asteroids-duel' });
    expect(webNativeSlug(c, manifest)).toBe('asteroids-duel');
    expect(webNativeBaseUrl(c, manifest)).toBe('/_remotes/wasm-fragment/asteroids-duel/index.html');
  });

  it('falls back to sceneType when chapter.slug is not a key (multi-chapter robustness)', () => {
    // A future web game could give chapters distinct slugs while sharing
    // the parent scene_type; the resolver still finds the row.
    const c = chapter({ slug: 'chess-endgame-7', sceneType: 'chess' });
    expect(isWebNativeChapter(c, manifest)).toBe(true);
    expect(webNativeSlug(c, manifest)).toBe('chess');
  });

  it('does NOT treat a federated/legacy chapter as web-native (no wasm-game row)', () => {
    // cave/field/dodecahedron are remote-game rows, not wasm-game, so
    // they never appear in this map — the federated path stays untouched.
    expect(isWebNativeChapter(chapter({ slug: 'cave', sceneType: 'cave' }), manifest)).toBe(false);
    expect(isWebNativeChapter(chapter({ slug: 'dodecahedron', sceneType: 'dodecahedron' }), manifest)).toBe(false);
    expect(resolveWebNativeRemote(chapter({ slug: 'cave', sceneType: 'cave' }), manifest)).toBeNull();
  });

  it('is null-safe and empty-map-safe (manifest not yet loaded)', () => {
    const empty = new Map<string, FederationRemote>();
    expect(isWebNativeChapter(null, manifest)).toBe(false);
    expect(isWebNativeChapter(chapter({ slug: 'chess' }), empty)).toBe(false);
    expect(webNativeSlug(null, manifest)).toBeNull();
    expect(webNativeBaseUrl(undefined, manifest)).toBeNull();
  });
});
