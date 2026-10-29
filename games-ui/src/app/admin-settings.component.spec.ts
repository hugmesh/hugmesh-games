import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { GamesAdminSettingsComponent, NOT_ON_THIS_MOUNT } from './admin-settings.component';

describe('GamesAdminSettingsComponent (A5)', () => {
  let http: HttpTestingController;
  let fixture: ComponentFixture<GamesAdminSettingsComponent>;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [GamesAdminSettingsComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(GamesAdminSettingsComponent);
    fixture.detectChanges();
  });

  afterEach(() => http.verify());
  const el = (): HTMLElement => fixture.nativeElement;

  it('loads the debug-mode flag into the toggle and always lists what is not on this mount', () => {
    expect(el().querySelector('[data-testid="games-admin-settings"]')).not.toBeNull();
    http.expectOne('/games/api/admin/debug-mode').flush({ wasmConsoleEnabled: true });
    fixture.detectChanges();
    const box = el().querySelector<HTMLInputElement>('[data-testid="games-admin-wasm-console"]')!;
    expect(box.checked).toBeTrue();
    const notHere = el().querySelector('[data-testid="games-admin-not-here"]')!;
    for (const item of NOT_ON_THIS_MOUNT) expect(notHere.textContent).toContain(item);
    expect(notHere.textContent).toContain('managed in the standalone games portal');
  });

  it('PUTs the wasm-console flag on change and reflects the response', () => {
    http.expectOne('/games/api/admin/debug-mode').flush({ wasmConsoleEnabled: false });
    fixture.detectChanges();
    const box = el().querySelector<HTMLInputElement>('[data-testid="games-admin-wasm-console"]')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    const req = http.expectOne('/games/api/admin/debug-mode/wasm-console');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ enabled: true });
    req.flush({ wasmConsoleEnabled: true });
    fixture.detectChanges();
    expect(fixture.componentInstance.wasmConsole()).toBeTrue();
  });

  it('shows an inline error and keeps the list when the PUT is refused', () => {
    http.expectOne('/games/api/admin/debug-mode').flush({ wasmConsoleEnabled: false });
    fixture.detectChanges();
    const box = el().querySelector<HTMLInputElement>('[data-testid="games-admin-wasm-console"]')!;
    box.checked = true;
    box.dispatchEvent(new Event('change'));
    http.expectOne('/games/api/admin/debug-mode/wasm-console').flush('', { status: 403, statusText: 'F' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-settings-error"]')?.textContent).toContain('refused');
    expect(el().querySelector('[data-testid="games-admin-not-here"]')).not.toBeNull();
  });

  it('renders the admin forbidden panel on 403 and the unreachable panel on 5xx', () => {
    http.expectOne('/games/api/admin/debug-mode').flush('', { status: 403, statusText: 'F', headers: { 'X-Route-Denied': '/games/api/admin/**' } });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-admin-forbidden"]')?.textContent).toContain('/games/api/admin/**');
    expect(el().querySelector('[data-testid="games-admin-not-here"]')).not.toBeNull();
    fixture.componentInstance.load();
    http.expectOne('/games/api/admin/debug-mode').flush('', { status: 500, statusText: 'E' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid="games-unreachable"]')).not.toBeNull();
  });
});
