import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { HomeComponent } from './home.component';
import { AvailableGame, GameWithChapters } from '../models/available-game.model';
import { CharacterState } from '../models/character.model';
import { GameSession, SessionMember } from '../services/multiplayer-session.service';
import { SessionLeaveOnUnloadService } from '../services/session-leave-on-unload.service';
import { SessionHeartbeatService } from '../services/session-heartbeat.service';
import { SessionControlService } from '../services/session-control.service';

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

  /**
   * D7 host launch fix: maker-built ('rpg') chapters take the ELSE branch
   * of selectChapter — neither federated nor web-native. That branch must
   * mint a launch uid for the umbrella GAME slug exactly like the other
   * two, and the slug must be captured from `modalGame` BEFORE the modal
   * is torn down (selectChapter nulls `modalGame` on entry).
   */
  describe('selectChapter — legacy/maker launch path', () => {
    function makerGame(): GameWithChapters {
      return {
        id: 5, name: 'Six Realms', description: 'maker-built', slug: 'six-realms',
        thumbnailUrl: null, sortOrder: 1, chapters: [],
      };
    }

    function rpgChapter(): AvailableGame {
      // scene_type 'rpg' is NOT in SCENE_TYPE_TO_GAME_SLUG and (with the
      // default empty web-native map) not web-native either → else branch.
      const c = level('six-realms-desert', 'rpg');
      c.assetUrl = 'desert/index.html'; // maker register.sql shape (relative)
      return c;
    }

    it('mints a launch for the game slug captured BEFORE the modal is nulled', () => {
      const httpMock = TestBed.inject(HttpTestingController);
      component.modalGame = makerGame();
      component.selectChapter(rpgChapter());

      // The modal is torn down synchronously…
      expect(component.modalGame).toBeNull();
      // …but the game slug survived the teardown and reached both the
      // canvas binding and the /launch mint.
      expect(component.activeGameSlug).toBe('six-realms');
      const req = httpMock.expectOne('/api/games/six-realms/launch');
      expect(req.request.method).toBe('POST');
      req.flush({ uid: 'uid-abc123', expiresAt: '2099-01-01T00:00:00Z', gameSlug: 'six-realms' });
      expect(component.activeLaunchUid).toBe('uid-abc123');
      httpMock.verify();
    });

    it('does not mint a launch when there is no modal game to derive a slug from', () => {
      const httpMock = TestBed.inject(HttpTestingController);
      component.modalGame = null;
      expect(() => component.selectChapter(rpgChapter())).not.toThrow();
      expect(component.activeGameSlug).toBeNull();
      httpMock.expectNone(r => r.url.endsWith('/launch'));
      httpMock.verify();
    });

    it('closeLevel clears the threaded game slug alongside the launch uid', () => {
      const httpMock = TestBed.inject(HttpTestingController);
      component.modalGame = makerGame();
      component.selectChapter(rpgChapter());
      httpMock.expectOne('/api/games/six-realms/launch')
        .flush({ uid: 'uid-abc123', expiresAt: '2099-01-01T00:00:00Z', gameSlug: 'six-realms' });

      component.closeLevel();

      expect(component.activeGameSlug).toBeNull();
      expect(component.activeLaunchUid).toBeNull();
      // Tearing down an active launch DELETEs the uid (existing behaviour).
      httpMock.expectOne('/api/games/launches/uid-abc123').flush(null);
      httpMock.verify();
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

/**
 * Seat release on an in-app chapter close.
 *
 * `MultiplayerSessionService.leave()` had NO caller anywhere in the frontend,
 * so closing a chapter deliberately — the polite exit, the one the UI offers —
 * left the membership row `left_at IS NULL` forever and held a seat plus an
 * avatar slot against the room's 16-player cap. A closed tab was only the
 * loudest symptom of a seat nothing ever released.
 */
describe('HomeComponent — releasing the multiplayer seat', () => {
  let fixture: ComponentFixture<HomeComponent>;
  let component: HomeComponent;
  let httpMock: HttpTestingController;
  let leaveOnUnload: SessionLeaveOnUnloadService;
  let heartbeat: SessionHeartbeatService;
  let sessionControl: SessionControlService;

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
    leaveOnUnload = TestBed.inject(SessionLeaveOnUnloadService);
    heartbeat = TestBed.inject(SessionHeartbeatService);
    sessionControl = TestBed.inject(SessionControlService);
  });

  const leaveUrl = (id: string) => `/api/sessions/${id}/me`;

  /** Put the component in the state of a player seated in a running chapter. */
  function seatedIn(sessionId: string): void {
    component.activeSessionId = sessionId;
    component.activeSlotIndex = 1;
    component.activeLevel = { id: 1, name: 'x', slug: 'x' } as unknown as AvailableGame;
    leaveOnUnload.track(sessionId);
    heartbeat.track(sessionId);
  }

  it('DELETEs the membership row when the chapter is closed in-app', () => {
    seatedIn('sess-9');

    component.closeLevel();

    const reqs = httpMock.match(r => r.url === leaveUrl('sess-9') && r.method === 'DELETE');
    expect(reqs.length).toBe(1);
    reqs[0].flush(null);
  });

  it('stops the unload handler tracking a seat it already released', () => {
    // Otherwise closing the chapter and then the tab fires two DELETEs, the
    // second against a membership row that is already stamped.
    seatedIn('sess-9');

    component.closeLevel();
    httpMock.match(r => r.url === leaveUrl('sess-9')).forEach(r => r.flush(null));

    expect(leaveOnUnload.tracked()).toBeNull();
    expect(component.activeSessionId).toBeNull();
  });

  it('issues no leave when the player was never in a session', () => {
    component.activeLevel = { id: 1, name: 'x', slug: 'x' } as unknown as AvailableGame;

    component.closeLevel();

    expect(httpMock.match(r => r.method === 'DELETE').length).toBe(0);
  });

  it('does not DELETE against a session the BFF already closed', () => {
    // SESSION_CLOSED means the room is gone server-side; a leave would be a
    // pointless request racing the close it is reacting to.
    seatedIn('sess-9');

    sessionControl.sessionClosed.set({ sessionId: 'sess-9', reason: 'host-disconnected' });
    fixture.detectChanges();   // flush the field-initialised effect

    expect(httpMock.match(r => r.url === leaveUrl('sess-9')).length).toBe(0);
    expect(leaveOnUnload.tracked()).toBeNull();
    expect(component.activeSessionId).toBeNull();
  });

  it('swallows a failed leave — the chapter is already closed in the UI', () => {
    seatedIn('sess-9');

    component.closeLevel();
    const req = httpMock.expectOne(r => r.url === leaveUrl('sess-9'));
    expect(() => req.flush('boom', { status: 500, statusText: 'Server Error' })).not.toThrow();
    expect(component.activeLevel).toBeNull();
  });

  it('hands the unload handler the seat as soon as one is joined', () => {
    component.joinSession({
      id: 'sess-3', name: 'Demo', gameSlug: 'shadows-of-the-net',
      creatorCasdoorId: 'alice', maxPlayers: 4, activePlayers: 1,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null,
      members: [{ casdoorId: 'alice', characterId: 1, slotIndex: 0, creator: true,
                  joinedAt: '2026-01-01T00:00:00Z' }],
    });
    const req = httpMock.expectOne(r => r.url === '/api/sessions/sess-3/join');
    req.flush({
      id: 'sess-3', name: 'Demo', gameSlug: 'shadows-of-the-net',
      creatorCasdoorId: 'alice', maxPlayers: 4, activePlayers: 2,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null, members: [],
    });

    expect(leaveOnUnload.tracked()).toBe('sess-3');
  });

  it('hands the unload handler the seat when the player creates the session', () => {
    component.modalGame = {
      slug: 'shadows-of-the-net', name: 'Shadows of the Net', description: '', chapters: [],
    } as unknown as GameWithChapters;
    component.newSessionName = 'Noir room';
    component.createSession();

    const req = httpMock.expectOne(r => r.url === '/api/sessions' && r.method === 'POST');
    req.flush({
      id: 'sess-4', name: 'Noir room', gameSlug: 'shadows-of-the-net',
      creatorCasdoorId: 'alice', maxPlayers: 3, activePlayers: 1,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null, members: [],
    });

    expect(leaveOnUnload.tracked()).toBe('sess-4');
  });

  // ── the superseded (frozen old) tab must not evict the live one ──────────
  //
  // A takeover pauses the OLD tab's IFRAME, not its toolbar: the Close button
  // renders on `activeLevel` alone and the frozen tab still holds
  // `activeSessionId`. `left_at` is per MEMBER, so a leave sent from here
  // stamps the row the NEW tab is playing under. The BFF cannot save us —
  // an explicit DELETE (no `unload` flag) is deliberately never dropped — and
  // if the member is the creator, `closeOnHostDrop` takes the whole room down
  // with them. This is the eviction the entire superseded latch exists to
  // prevent, and the unload leg alone did not cover it.

  it('does NOT evict the new tab when a superseded tab clicks Close', () => {
    seatedIn('sess-9');
    sessionControl.superseded.set(true);

    component.closeLevel();

    expect(httpMock.match(r => r.url === leaveUrl('sess-9') && r.method === 'DELETE').length)
      .withContext('a frozen tab must send no leave for the seat its successor is playing on')
      .toBe(0);
  });

  it('still tears the frozen chapter down locally when superseded', () => {
    // Suppressing the leave must not turn the Close button into a no-op —
    // the player asked to be rid of the dead iframe and gets that.
    seatedIn('sess-9');
    sessionControl.superseded.set(true);

    component.closeLevel();

    expect(component.activeLevel).toBeNull();
    expect(component.activeSessionId).toBeNull();
    expect(leaveOnUnload.tracked()).toBeNull();
    expect(heartbeat.tracked()).toBeNull();
  });

  it('resumes releasing the seat once this tab re-claims the game', () => {
    // The latch is not a one-way trip any more: coming back to the old tab
    // and re-launching makes it live again, and a live tab must release its
    // own seat on close or it ghosts for the whole reaper window.
    seatedIn('sess-9');
    sessionControl.superseded.set(true);
    sessionControl.claimTakeover({ gameSlug: 'chess', launchUid: 'uid-2', sessionId: 'sess-9' });

    component.closeLevel();

    expect(httpMock.match(r => r.url === leaveUrl('sess-9') && r.method === 'DELETE').length).toBe(1);
  });

  // ── the liveness heartbeat: the seat-keeping leg every game shares ───────

  it('starts the liveness heartbeat as soon as a seat is joined', () => {
    component.joinSession({
      id: 'sess-3', name: 'Demo', gameSlug: 'chess',
      creatorCasdoorId: 'alice', maxPlayers: 4, activePlayers: 1,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null,
      members: [{ casdoorId: 'alice', characterId: 1, slotIndex: 0, creator: true,
                  joinedAt: '2026-01-01T00:00:00Z' }],
    });
    httpMock.expectOne(r => r.url === '/api/sessions/sess-3/join').flush({
      id: 'sess-3', name: 'Demo', gameSlug: 'chess',
      creatorCasdoorId: 'alice', maxPlayers: 4, activePlayers: 2,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null, members: [],
    });

    expect(heartbeat.tracked())
      .withContext('chess emits no idle signal of its own; the shell is the only heartbeat it has')
      .toBe('sess-3');
  });

  it('starts the liveness heartbeat when the player creates the session', () => {
    component.modalGame = {
      slug: 'go', name: 'Go', description: '', chapters: [],
    } as unknown as GameWithChapters;
    component.newSessionName = 'Slow game';
    component.createSession();

    httpMock.expectOne(r => r.url === '/api/sessions' && r.method === 'POST').flush({
      id: 'sess-4', name: 'Slow game', gameSlug: 'go',
      creatorCasdoorId: 'alice', maxPlayers: 2, activePlayers: 1,
      visibility: 'public', createdAt: '2026-01-01T00:00:00Z', closedAt: null, members: [],
    });

    expect(heartbeat.tracked()).toBe('sess-4');
  });

  it('stops the liveness heartbeat once the seat is released', () => {
    seatedIn('sess-9');

    component.closeLevel();
    httpMock.match(r => r.url === leaveUrl('sess-9')).forEach(r => r.flush(null));

    expect(heartbeat.tracked()).toBeNull();
  });
});
