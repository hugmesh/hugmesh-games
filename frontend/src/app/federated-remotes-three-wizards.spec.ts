import { TestBed } from '@angular/core/testing';
import { DomSanitizer } from '@angular/platform-browser';

// Cross-project import — see federated-remotes-admin.spec.ts for why
// the spec lives in src/ instead of projects/games-three-wizards/.
import { ThreeWizardsRemoteComponent } from '../../projects/games-three-wizards/src/app/three-wizards-remote.component';

// Specs for the federated three-wizards remote. The component owns
// the iframe-URL contract that the patched Godot index.html depends
// on — every level transition flows through here, and a missing
// `level_id` query falls back to level 1 silently. Pinning the URL
// shape catches accidental drift before e2e does.
describe('ThreeWizardsRemoteComponent', () => {
  let component: ThreeWizardsRemoteComponent;
  let trustSpy: jasmine.Spy;

  beforeEach(() => {
    TestBed.configureTestingModule({});
    // Spy on bypassSecurityTrustResourceUrl so we can read the raw URL
    // strings the component builds — SafeResourceUrl is opaque, but
    // the sanitizer call is the seam where the input lives.
    const sanitizer = TestBed.inject(DomSanitizer);
    trustSpy = spyOn(sanitizer, 'bypassSecurityTrustResourceUrl')
      .and.callFake((url: string) => url as unknown as ReturnType<DomSanitizer['bypassSecurityTrustResourceUrl']>);
    component = TestBed.createComponent(ThreeWizardsRemoteComponent).componentInstance;
  });

  function lastUrl(): string {
    expect(trustSpy.calls.count()).toBeGreaterThan(0);
    return trustSpy.calls.mostRecent().args[0] as string;
  }

  it('defaults to /_remotes/wasm-fragment/cave/index.html before any input is set', () => {
    expect(lastUrl()).toBe('/_remotes/wasm-fragment/cave/index.html');
  });

  it('updates the iframe URL when chapterSlug input changes', () => {
    component.chapterSlug = 'field';
    const url = lastUrl();
    expect(url).toMatch(/^\/_remotes\/wasm-fragment\/field\/index\.html\?/);
  });

  it('falls back to cave when chapterSlug is empty (handles unset host input)', () => {
    component.chapterSlug = '';
    const url = lastUrl();
    expect(url).toMatch(/^\/_remotes\/wasm-fragment\/cave\/index\.html\?/);
  });

  it('threads chapterId into the level_id query so the prefetch script picks it up', () => {
    component.chapterSlug = 'cave';
    component.chapterId = 7;
    const url = lastUrl();
    // The patched Godot index.html reads URLSearchParams.get('level_id')
    // — without this query, scene prefetch defaults to level 1.
    expect(url).toContain('level_id=7');
    expect(url).toContain('/_remotes/wasm-fragment/cave/index.html?');
  });

  it('omits level_id from the URL when the input is null', () => {
    component.chapterSlug = 'cave';
    component.chapterId = null;
    const url = lastUrl();
    // With no chapterId, the URLSearchParams build skips the param
    // entirely (rather than emitting level_id=undefined or
    // level_id=&). Just t= remains as the cache-buster.
    expect(url).not.toContain('level_id=');
    expect(url).toMatch(/[?&]t=\d+/);
  });

  it('threads launchUid into the launch_uid query for audit traceability', () => {
    component.chapterSlug = 'cave';
    component.chapterId = 1;
    component.launchUid = 'fake-launch-uid-43chars';
    const url = lastUrl();
    expect(url).toContain('launch_uid=fake-launch-uid-43chars');
  });

  it('omits launch_uid when the input is null', () => {
    component.chapterSlug = 'cave';
    component.launchUid = null;
    const url = lastUrl();
    expect(url).not.toContain('launch_uid');
  });

  it('cache-busts every refresh so the iframe remounts cleanly', () => {
    component.chapterSlug = 'cave';
    const first = lastUrl();
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(Date.now() + 50));
    component.chapterSlug = 'field';
    jasmine.clock().uninstall();
    const second = lastUrl();
    expect(first).not.toBe(second);
    // Both URLs carry a t= cache-buster regardless of param ordering.
    // URLSearchParams emits the first param after `?` (no leading `&`),
    // so the regex tolerates either separator.
    expect(first).toMatch(/[?&]t=\d+$/);
    expect(second).toMatch(/[?&]t=\d+$/);
  });
});
