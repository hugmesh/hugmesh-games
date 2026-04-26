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

  it('defaults to /api/assets/levels/cave/index.html before any input is set', () => {
    expect(lastUrl()).toBe('/api/assets/levels/cave/index.html');
  });

  it('updates the iframe URL when levelSlug input changes', () => {
    component.levelSlug = 'field';
    const url = lastUrl();
    expect(url).toMatch(/^\/api\/assets\/levels\/field\/index\.html\?/);
  });

  it('falls back to cave when levelSlug is empty (handles unset host input)', () => {
    component.levelSlug = '';
    const url = lastUrl();
    expect(url).toMatch(/^\/api\/assets\/levels\/cave\/index\.html\?/);
  });

  it('threads levelId into the level_id query so the prefetch script picks it up', () => {
    component.levelSlug = 'cave';
    component.levelId = 7;
    const url = lastUrl();
    // The patched Godot index.html reads URLSearchParams.get('level_id')
    // — without this query, scene prefetch defaults to level 1.
    expect(url).toContain('level_id=7');
    expect(url).toContain('/api/assets/levels/cave/index.html?');
  });

  it('omits level_id value when the input is null (host hasn\'t set it yet)', () => {
    component.levelSlug = 'cave';
    component.levelId = null;
    const url = lastUrl();
    // With no levelId, the param is empty rather than undefined-stringified.
    expect(url).toContain('level_id=&');
  });

  it('cache-busts every refresh so the iframe remounts cleanly', () => {
    component.levelSlug = 'cave';
    const first = lastUrl();
    // Force the timestamp to advance — modern browsers do this naturally
    // but karma's Date is real, so wait one tick by triggering another
    // setter invocation. Each call is wallclock-stamped via Date.now().
    jasmine.clock().install();
    jasmine.clock().mockDate(new Date(Date.now() + 50));
    component.levelSlug = 'field';
    jasmine.clock().uninstall();
    const second = lastUrl();
    expect(first).not.toBe(second);
    expect(first).toMatch(/&t=\d+$/);
    expect(second).toMatch(/&t=\d+$/);
  });
});
