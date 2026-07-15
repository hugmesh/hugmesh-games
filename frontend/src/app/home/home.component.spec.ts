import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { HomeComponent } from './home.component';
import { AvailableGame, GameWithChapters } from '../models/available-game.model';

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
        provideHttpClient(withXhr()),
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
    // The federation cutover put TW_CHAPTER_TRANSITION at the window
    // level on HomeComponent so it fires regardless of which renderer
    // mounts the iframe. Pinning the predicate keeps stray window
    // messages (extension chatter, dev-tools messages) from reaching
    // onChapterTransition.
    it('forwards a well-formed TW_CHAPTER_TRANSITION to onChapterTransition', () => {
      const spy = spyOn(component, 'onChapterTransition');
      const ev = new MessageEvent('message', {
        data: { type: 'TW_CHAPTER_TRANSITION', slug: 'field', chapterId: 2 },
      });
      component.onWindowMessage(ev);
      expect(spy).toHaveBeenCalledWith({ slug: 'field', chapterId: 2 });
    });

    it('ignores messages with the wrong type', () => {
      const spy = spyOn(component, 'onChapterTransition');
      component.onWindowMessage(new MessageEvent('message', {
        data: { type: 'GAME_STATE', slug: 'field', chapterId: 2 },
      }));
      expect(spy).not.toHaveBeenCalled();
    });

    it('ignores messages with missing or wrong-typed slug/chapterId', () => {
      const spy = spyOn(component, 'onChapterTransition');
      // chapterId is a string, not a number
      component.onWindowMessage(new MessageEvent('message', {
        data: { type: 'TW_CHAPTER_TRANSITION', slug: 'field', chapterId: '2' },
      }));
      // missing slug
      component.onWindowMessage(new MessageEvent('message', {
        data: { type: 'TW_CHAPTER_TRANSITION', chapterId: 2 },
      }));
      // null payload (chrome extension noise)
      component.onWindowMessage(new MessageEvent('message', { data: null }));
      // primitive payload (someone postMessage'd a string)
      component.onWindowMessage(new MessageEvent('message', { data: 'hello' }));
      expect(spy).not.toHaveBeenCalled();
    });
  });
});

/**
 * Keyboard-trap remediation coverage for the chapter/session-picker
 * modal (appFocusTrap on .modal-backdrop). Sets `modalGame` directly
 * rather than going through `openGame()` so the test doesn't depend on
 * the (unrelated) multiplayer-session HTTP fetch resolving — the modal
 * renders identically either way since the template only reads
 * `modalGame` + `modalSessions`/`modalSessionsLoading` (both default to
 * `[]` / `false`, matching the picker's "no open sessions yet" state).
 */
describe('HomeComponent chapter modal — focus trap', () => {
  let fixture: ComponentFixture<HomeComponent>;
  let component: HomeComponent;
  let outsideTrigger: HTMLButtonElement;

  function fakeGame(): GameWithChapters {
    const chapter: AvailableGame = {
      id: 1, gameId: 1, name: 'Cave', description: 'A cave chapter.', slug: 'cave',
      sceneType: 'cave', assetUrl: null, thumbnailUrl: null, sortOrder: 1,
      unlocked: true, requiredQuestIds: [], completedQuestIds: [],
      totalQuests: 0, completedQuests: 0,
    };
    return {
      id: 1, name: 'Three Wizards', description: 'desc', slug: 'three-wizards',
      thumbnailUrl: null, sortOrder: 1, chapters: [chapter],
    };
  }

  function tabEvent(shiftKey: boolean): KeyboardEvent {
    return new KeyboardEvent('keydown', { key: 'Tab', shiftKey, bubbles: true, cancelable: true });
  }

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    fixture = TestBed.createComponent(HomeComponent);
    component = fixture.componentInstance;
    outsideTrigger = document.createElement('button');
    outsideTrigger.textContent = 'outside trigger';
    document.body.appendChild(outsideTrigger);
    document.body.appendChild(fixture.nativeElement);
    outsideTrigger.focus();
    fixture.detectChanges(); // runs ngOnInit (games$/session HTTP calls go unflushed — harmless)
  });

  afterEach(() => {
    fixture.nativeElement.remove();
    outsideTrigger.remove();
  });

  function firstFocusable(): HTMLElement {
    return fixture.nativeElement.querySelector('.modal-backdrop .close-btn');
  }

  function lastFocusable(): HTMLElement {
    // The only unlocked chapter's Play button is the last focusable
    // element in DOM order (after the close button and the session
    // picker's create-session form, whose submit button stays disabled
    // — and therefore not focusable — while newSessionName is empty).
    return fixture.nativeElement.querySelector('.chapter-row .play-btn:not(.play-btn--locked)');
  }

  it('moves focus to the close button on open and remembers the triggering element', () => {
    component.modalGame = fakeGame();
    fixture.detectChanges();
    expect(document.activeElement).toBe(firstFocusable());
  });

  it('Tab from the LAST focusable element (Play) wraps to the FIRST (Close)', () => {
    component.modalGame = fakeGame();
    fixture.detectChanges();

    const last = lastFocusable();
    const first = firstFocusable();
    last.focus();
    expect(document.activeElement).toBe(last);

    const ev = tabEvent(false);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    last.dispatchEvent(ev);

    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(first);
  });

  it('Shift+Tab from the FIRST focusable element (Close) wraps to the LAST (Play)', () => {
    component.modalGame = fakeGame();
    fixture.detectChanges();

    const first = firstFocusable();
    const last = lastFocusable();
    first.focus();
    expect(document.activeElement).toBe(first);

    const ev = tabEvent(true);
    const preventSpy = spyOn(ev, 'preventDefault').and.callThrough();
    first.dispatchEvent(ev);

    expect(preventSpy).toHaveBeenCalled();
    expect(document.activeElement).toBe(last);
  });

  it('restores focus to the triggering element when the modal closes', () => {
    component.modalGame = fakeGame();
    fixture.detectChanges();
    expect(document.activeElement).not.toBe(outsideTrigger);

    component.closeModal();
    fixture.detectChanges();

    expect(document.activeElement).toBe(outsideTrigger);
  });
});
