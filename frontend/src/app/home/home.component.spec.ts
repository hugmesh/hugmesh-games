import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { HomeComponent } from './home.component';
import { AvailableGame, GameWithChapters } from '../models/available-game.model';
import { CharacterState } from '../models/character.model';
import { GameSession, SessionMember } from '../services/multiplayer-session.service';

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

/**
 * Character picker coverage. The picker consumes GET /api/characters
 * (the caller's own roster) so two real players in one session default
 * to DISTINCT character_ids instead of both landing on the seeded Hero
 * (id 1) and colliding on the per-character control authorization. These
 * tests pin the two load-bearing behaviours: the owned-character default
 * in ngOnInit and the selectCharacter() guard rails.
 */
describe('HomeComponent character picker', () => {
  let fixture: ComponentFixture<HomeComponent>;
  let component: HomeComponent;
  let httpMock: HttpTestingController;

  const CHARACTERS_URL = 'http://localhost:8080/api/characters';

  function character(id: number, name: string, level = 1): CharacterState {
    return {
      id, name, level, xp: 0, xpToNext: 100,
      maxHp: 30, currentHp: 30, maxMp: 10, currentMp: 10,
      attack: 5, defense: 5, speed: 5, magicPower: 5, currentChapterId: null,
    };
  }

  beforeEach(() => {
    // Keep any prior spec's ?characterId= override from leaking in — the
    // default-selection assertions below depend on a clean URL.
    if (window.location.search) {
      window.history.replaceState({}, '', window.location.pathname);
    }
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
      ],
    });
    fixture = TestBed.createComponent(HomeComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  /** Flush the ngOnInit GET /api/characters with the given roster and
   *  ignore the sibling ngOnInit fetches (games / stats / session
   *  connect) — none are under test here, so we leave them unflushed. */
  function flushRoster(chars: CharacterState[]): void {
    fixture.detectChanges(); // runs ngOnInit → fires the listMine() GET
    const req = httpMock.match(r => r.url === CHARACTERS_URL && r.method === 'GET');
    expect(req.length).toBe(1);
    req[0].flush(chars);
  }

  it('defaults characterId to the caller\'s lowest-id owned character (not the hardcoded 1)', () => {
    flushRoster([character(7, 'Mage'), character(3, 'Rogue')]);
    // Sorted ascending → id 3 is first; 3 replaces the hardcoded default 1.
    expect(component.characterId).toBe(3);
    expect(component.myCharacters.map(c => c.id)).toEqual([3, 7]);
  });

  it('keeps the hardcoded default when the owned list already contains id 1', () => {
    flushRoster([character(1, 'Hero'), character(9, 'Cleric')]);
    expect(component.characterId).toBe(1);
  });

  it('leaves characterId at 1 when the caller owns no characters', () => {
    flushRoster([]);
    expect(component.characterId).toBe(1);
    expect(component.myCharacters).toEqual([]);
  });

  it('selectCharacter switches the active character and resolves selectedCharacter', () => {
    flushRoster([character(3, 'Rogue'), character(7, 'Mage')]);
    expect(component.selectedCharacter?.id).toBe(3);
    component.selectCharacter(7);
    expect(component.characterId).toBe(7);
    expect(component.selectedCharacter?.name).toBe('Mage');
  });

  it('selectCharacter is a no-op while a session is already active (no mid-session swap)', () => {
    flushRoster([character(3, 'Rogue'), character(7, 'Mage')]);
    component.activeSessionId = 'sess-123';
    component.selectCharacter(7);
    expect(component.characterId).toBe(3); // unchanged
  });

  it('respects the ?characterId= URL override and does NOT clobber it with the owned default', () => {
    window.history.replaceState({}, '', window.location.pathname + '?characterId=42');
    try {
      flushRoster([character(3, 'Rogue'), character(7, 'Mage')]);
      expect(component.characterId).toBe(42);
    } finally {
      window.history.replaceState({}, '', window.location.pathname);
    }
  });
});

/**
 * Slot 0 is the CREATOR's slot. Host-authoritative Godot titles elect their
 * in-game host from the client-visible `slot_index` — shadows-of-the-net does
 * `_is_host = slot_index == 0` — while the BFF grants authority on
 * `SessionMember.isCreator()` (InputEventController's Gate 0b). The lobby's
 * slot picker used to start its scan at 0, so any session whose creator row
 * carried no slot handed slot 0 to the first GUEST. That guest then believed
 * itself the room host, arbitrated seats / stage / media placements for
 * everyone, and had every authoritative broadcast 403'd — which the game
 * turned into SD_SESSION_EXPIRED, so the host shell re-ran connect +
 * claimControl on every seat click and stage toggle, forever, while the real
 * creator never broadcast at all.
 *
 * The BFF now pins the creator to slot 0 and 409s any other member that asks
 * for it; this spec pins the client half — the picker must never even offer
 * it, including for legacy sessions whose creator row predates the pin.
 */
describe('HomeComponent session slot picker', () => {
  let fixture: ComponentFixture<HomeComponent>;
  let component: HomeComponent;
  let httpMock: HttpTestingController;

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
    httpMock = TestBed.inject(HttpTestingController);
  });

  function member(casdoorId: string, slotIndex: number | null, creator = false): SessionMember {
    return { casdoorId, characterId: 1, slotIndex, creator, joinedAt: '2026-01-01T00:00:00Z' };
  }

  function session(maxPlayers: number, members: SessionMember[]): GameSession {
    return {
      id: 'sess-1', name: 'Demo', gameSlug: 'shadows-of-the-net',
      creatorCasdoorId: 'alice', maxPlayers, activePlayers: members.length,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null, members,
    };
  }

  /** MultiplayerSessionService posts RELATIVE paths (no environment.apiBase
   *  prefix, unlike CharacterService) — match on the path as issued. */
  const joinUrl = (id: string) => `/api/sessions/${id}/join`;

  /** Drive the real public join path and return the slotIndex it POSTs. */
  function slotSentForJoin(s: GameSession): number | undefined {
    component.joinSession(s);
    const reqs = httpMock.match(r => r.url === joinUrl(s.id) && r.method === 'POST');
    expect(reqs.length).toBe(1);
    const slot = (reqs[0].request.body as { slotIndex?: number }).slotIndex;
    reqs[0].flush(s);
    return slot;
  }

  /** Drive the join path and return how many join POSTs it produced. */
  function joinRequestCount(s: GameSession): number {
    component.joinSession(s);
    return httpMock.match(r => r.url === joinUrl(s.id) && r.method === 'POST').length;
  }

  it('never hands slot 0 to a joining guest, even when nobody has claimed it', () => {
    // The exact legacy shape: a creator row that predates the slot pin.
    const slot = slotSentForJoin(session(4, [member('alice', null, true)]));
    expect(slot).not.toBe(0);
    expect(slot).toBe(1);
  });

  it('skips claimed slots and returns the lowest free non-creator slot', () => {
    const slot = slotSentForJoin(session(4, [
      member('alice', 0, true), member('bob', 1), member('carol', 3),
    ]));
    expect(slot).toBe(2);
  });

  it('reports the session full rather than falling back to slot 0', () => {
    // maxPlayers 3 with slots 1 and 2 taken: only 0 is free, and it is the
    // creator's — the guest must be told the session is full, never re-seated
    // onto the host slot.
    expect(joinRequestCount(session(3, [member('bob', 1), member('carol', 2)]))).toBe(0);
  });

  it('a single-slot session is full for a guest (slot 0 belongs to the creator)', () => {
    expect(joinRequestCount(session(1, []))).toBe(0);
  });

  it('createSession still claims slot 0 for the creator', () => {
    component.modalGame = {
      slug: 'shadows-of-the-net', name: 'Shadows of the Net', description: '',
      chapters: [],
    } as unknown as GameWithChapters;
    component.newSessionName = 'Noir room';
    component.createSession();

    const reqs = httpMock.match(r => r.url === '/api/sessions' && r.method === 'POST');
    expect(reqs.length).toBe(1);
    expect((reqs[0].request.body as { slotIndex?: number }).slotIndex).toBe(0);
  });
});
