import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Router, RouterOutlet, provideRouter } from '@angular/router';
import { GAMES_ADMIN_ROUTES } from './games-admin.routes';
import { GAMES_ROUTES } from './games.routes';
import { GamesCatalogComponent } from './catalog.component';
import { GamePlayComponent } from './play.component';
import { GamesAdminShellComponent } from './admin-shell.component';
import { NEW_TAB_SLUGS, WEB_NATIVE_SLUGS, bundleUrl } from './games.constants';

@Component({ standalone: true, imports: [RouterOutlet], template: '<router-outlet></router-outlet>' })
class HostStub {}

/**
 * The federation CONTRACT the host shell relies on: './routes' exports
 * GAMES_ROUTES, './admin-routes' exports GAMES_ADMIN_ROUTES, with the paths
 * the designer's screens name — and every in-app link stays RELATIVE to the
 * host's mount so the shell's root router resolves it under /games(/admin).
 */
describe('exposed route constants', () => {
  it('GAMES_ROUTES mounts the catalog at "" and play at "play/:slug"', () => {
    expect(GAMES_ROUTES.map((r) => r.path)).toEqual(['', 'play/:slug']);
    expect(GAMES_ROUTES[0].component).toBe(GamesCatalogComponent);
    expect(GAMES_ROUTES[1].component).toBe(GamePlayComponent);
  });

  it('GAMES_ADMIN_ROUTES is one shell with the five admin children', () => {
    expect(GAMES_ADMIN_ROUTES.length).toBe(1);
    expect(GAMES_ADMIN_ROUTES[0].component).toBe(GamesAdminShellComponent);
    expect(GAMES_ADMIN_ROUTES[0].children!.map((r) => r.path)).toEqual([
      '',
      'sessions',
      'sessions/:id',
      'logs',
      'access',
      'settings',
    ]);
  });

  it('the new-tab and web-native bundle lists are the contract constants', () => {
    expect([...NEW_TAB_SLUGS].sort()).toEqual(['cave', 'chess-godot', 'dodecahedron', 'field', 'shadows-of-the-net']);
    expect(WEB_NATIVE_SLUGS.length).toBe(14);
    expect(bundleUrl('chess', 'u/1')).toBe('/_remotes/wasm-fragment/chess/index.html?launch_uid=u%2F1');
  });
});

describe('links under a host-like mount', () => {
  let router: Router;
  let location: Location;
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [HostStub],
      providers: [
        // The host shell's shape: 'games/admin' BEFORE 'games', both at the ROOT router.
        provideRouter([
          { path: 'games/admin', children: GAMES_ADMIN_ROUTES },
          { path: 'games', children: GAMES_ROUTES },
        ]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    }).compileComponents();
    router = TestBed.inject(Router);
    location = TestBed.inject(Location);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('catalog Play links resolve to /games/play/<slug> (not /play/<slug>)', async () => {
    const fixture = TestBed.createComponent(HostStub);
    await router.navigateByUrl('/games');
    fixture.detectChanges();
    http.expectOne('/games/api/games').flush([{ id: 1, name: 'Chess', slug: 'chess', description: null, thumbnailUrl: null, sortOrder: 0 }]);
    http.expectOne('/games/api/federation/manifest').flush({ generatedAt: '', remotes: [{ slug: 'x', kind: 'wasm-game', gameSlug: 'chess', remoteEntryUrl: '', displayName: null, exposedModule: null, version: null, metadata: null }] });
    fixture.detectChanges();
    http.expectNone('/games/api/games/launches'); // no games_admin session: not asked
    fixture.detectChanges();
    const play = fixture.nativeElement.querySelector('[data-testid="games-play-chess"]') as HTMLAnchorElement;
    expect(play.getAttribute('href')).toBe('/games/play/chess');
  });

  it('admin tabs resolve under /games/admin and the Overview tab is exact-active only at the root', async () => {
    const fixture = TestBed.createComponent(HostStub);
    await router.navigateByUrl('/games/admin/settings');
    fixture.detectChanges();
    http.expectOne('/games/api/admin/debug-mode').flush({ wasmConsoleEnabled: false });
    fixture.detectChanges();
    // RouterLinkActive applies its class in a microtask after NavigationEnd.
    await fixture.whenStable();
    fixture.detectChanges();
    const hrefs = Array.from(fixture.nativeElement.querySelectorAll('[data-testid^="games-admin-tab-"]')).map(
      (a) => (a as HTMLAnchorElement).getAttribute('href'),
    );
    expect(hrefs).toEqual(['/games/admin', '/games/admin/sessions', '/games/admin/logs', '/games/admin/access', '/games/admin/settings']);
    expect(location.path()).toBe('/games/admin/settings');
    const overview = fixture.nativeElement.querySelector('[data-testid="games-admin-tab-overview"]') as HTMLElement;
    const settings = fixture.nativeElement.querySelector('[data-testid="games-admin-tab-settings"]') as HTMLElement;
    expect(overview.classList.contains('tab--active')).toBeFalse();
    expect(settings.classList.contains('tab--active')).toBeTrue();
  });
});
