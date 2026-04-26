import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { HomeComponent } from './home.component';
import { AvailableGame } from '../models/available-game.model';

/**
 * Pure-helper coverage for HomeComponent. Heavier orchestration is
 * exercised by `make e2e`; this spec targets the small synchronous
 * predicates that drive the template's @if / @switch branches —
 * those are the safety rails for HUD suppression and the federated
 * render path, both already user-visible.
 *
 * Now testable in karma because RemoteLoaderService dynamic-imports
 * `@angular-architects/native-federation` instead of statically
 * importing it (the static import broke karma's webpack-based parser).
 */
describe('HomeComponent helpers', () => {
  let component: HomeComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    component = TestBed.createComponent(HomeComponent).componentInstance;
  });

  function level(slug: string, sceneType: string): AvailableGame {
    return {
      id: 1, gameId: 1, name: slug, description: '', slug,
      sceneType, assetUrl: '', thumbnailUrl: null, sortOrder: 1,
      unlocked: true, requiredQuestIds: [], completedQuestIds: [],
      totalQuests: 0, completedQuests: 0,
    };
  }

  describe('sceneIcon', () => {
    it('returns the registered icon for a known sceneType', () => {
      expect(component.sceneIcon(level('cave', 'cave'))).toBe('🏔');
      expect(component.sceneIcon(level('field', 'field'))).toBe('🌿');
      expect(component.sceneIcon(level('dodecahedron', 'dodecahedron'))).toBe('⬡');
    });

    it('falls back to the sword glyph for unknown sceneTypes', () => {
      expect(component.sceneIcon(level('mystery', 'unknown-type'))).toBe('⚔');
    });
  });

  describe('isChromeless', () => {
    it('returns true for sceneTypes in CHROMELESS_SCENE_TYPES', () => {
      expect(component.isChromeless(level('dodecahedron', 'dodecahedron'))).toBeTrue();
    });

    it('returns false for sceneTypes outside the chromeless set', () => {
      expect(component.isChromeless(level('cave', 'cave'))).toBeFalse();
      expect(component.isChromeless(level('field', 'field'))).toBeFalse();
    });

    it('returns false for null', () => {
      expect(component.isChromeless(null)).toBeFalse();
    });
  });

  describe('isFederated', () => {
    it('returns true for sceneTypes mapped to a federated gameSlug', () => {
      // dodecahedron sceneType → dodecahedron gameSlug → federated
      expect(component.isFederated(level('dodecahedron', 'dodecahedron'))).toBeTrue();
      // cave + field both belong to the Three Wizards umbrella game and
      // share the games-three-wizards federated remote.
      expect(component.isFederated(level('cave', 'cave'))).toBeTrue();
      expect(component.isFederated(level('field', 'field'))).toBeTrue();
    });

    it('returns false for sceneTypes outside SCENE_TYPE_TO_GAME_SLUG', () => {
      // Unknown sceneTypes have no mapping, so they fall through to
      // the legacy <app-game-canvas> branch.
      expect(component.isFederated(level('mystery', 'mystery'))).toBeFalse();
    });

    it('returns false for null', () => {
      expect(component.isFederated(null)).toBeFalse();
    });
  });

  describe('onWindowMessage', () => {
    // The federation cutover put TW_LEVEL_TRANSITION at the window
    // level on HomeComponent so it fires regardless of which renderer
    // mounts the iframe. Pinning the predicate keeps stray window
    // messages (extension chatter, dev-tools messages) from reaching
    // onLevelTransition.
    it('forwards a well-formed TW_LEVEL_TRANSITION to onLevelTransition', () => {
      const spy = spyOn(component, 'onLevelTransition');
      const ev = new MessageEvent('message', {
        data: { type: 'TW_LEVEL_TRANSITION', slug: 'field', levelId: 2 },
      });
      component.onWindowMessage(ev);
      expect(spy).toHaveBeenCalledWith({ slug: 'field', levelId: 2 });
    });

    it('ignores messages with the wrong type', () => {
      const spy = spyOn(component, 'onLevelTransition');
      component.onWindowMessage(new MessageEvent('message', {
        data: { type: 'GAME_STATE', slug: 'field', levelId: 2 },
      }));
      expect(spy).not.toHaveBeenCalled();
    });

    it('ignores messages with missing or wrong-typed slug/levelId', () => {
      const spy = spyOn(component, 'onLevelTransition');
      // levelId is a string, not a number
      component.onWindowMessage(new MessageEvent('message', {
        data: { type: 'TW_LEVEL_TRANSITION', slug: 'field', levelId: '2' },
      }));
      // missing slug
      component.onWindowMessage(new MessageEvent('message', {
        data: { type: 'TW_LEVEL_TRANSITION', levelId: 2 },
      }));
      // null payload (chrome extension noise)
      component.onWindowMessage(new MessageEvent('message', { data: null }));
      // primitive payload (someone postMessage'd a string)
      component.onWindowMessage(new MessageEvent('message', { data: 'hello' }));
      expect(spy).not.toHaveBeenCalled();
    });
  });
});
