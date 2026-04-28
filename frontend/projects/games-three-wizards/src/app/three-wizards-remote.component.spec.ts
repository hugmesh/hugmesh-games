import { TestBed } from '@angular/core/testing';
import { ThreeWizardsRemoteComponent } from './three-wizards-remote.component';

/**
 * URL-shape specs for ThreeWizardsRemoteComponent. The inputs feed
 * directly into the iframe URL — these tests pin that
 * sessionId / slotIndex / localCharacterId thread through so the Godot
 * bundle's game_bridge.gd sees them on _ready and flips HostBridge
 * into multiplayer mode.
 */
describe('ThreeWizardsRemoteComponent', () => {
  let comp: ThreeWizardsRemoteComponent;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(ThreeWizardsRemoteComponent);
    comp = fixture.componentInstance;
  });

  function urlString(): string {
    // SafeResourceUrl is opaque at runtime; reach into the internal
    // _frameUrl via the toolbar helper which exposes the same string.
    return (comp as unknown as { _frameUrl: string })._frameUrl;
  }

  it('default URL is the cave bundle with no multiplayer params', () => {
    comp.chapterSlug = 'cave';
    comp.chapterId   = 1;
    const url = urlString();
    expect(url).toContain('/_remotes/wasm-fragment/cave/index.html');
    expect(url).toContain('chapter_id=1');
    expect(url).not.toContain('session_id=');
    expect(url).not.toContain('slot_index=');
  });

  it('threading sessionId + slotIndex + localCharacterId through carries them on the iframe URL', () => {
    comp.chapterSlug = 'cave';
    comp.chapterId   = 1;
    comp.sessionId   = '00000000-0000-0000-0000-000000000001';
    comp.slotIndex   = 2;
    comp.localCharacterId = 7;
    const url = urlString();
    expect(url).toContain('session_id=00000000-0000-0000-0000-000000000001');
    expect(url).toContain('slot_index=2');
    expect(url).toContain('character_id=7');
  });

  it('clearing sessionId removes it from the URL on next refresh', () => {
    comp.chapterSlug = 'cave'; comp.chapterId = 1;
    comp.sessionId = 'sess-A';
    expect(urlString()).toContain('session_id=sess-A');
    comp.sessionId = null;
    expect(urlString()).not.toContain('session_id=');
  });

  it('preserves launchUid even when multiplayer params are also set', () => {
    comp.chapterSlug = 'field';
    comp.chapterId   = 2;
    comp.launchUid   = 'lu-123';
    comp.sessionId   = 'sess-B';
    comp.slotIndex   = 0;
    const url = urlString();
    expect(url).toContain('launch_uid=lu-123');
    expect(url).toContain('session_id=sess-B');
    expect(url).toContain('slot_index=0');
  });
});
