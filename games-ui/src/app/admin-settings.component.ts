import { Component, OnInit, inject, signal } from '@angular/core';
import { ApiFailure, GamesApiService, classifyFailure } from './games-api.service';
import { GamesAdminForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

/** What this mount does NOT offer, stated so an admin is told, not left guessing. */
export const NOT_ON_THIS_MOUNT: readonly string[] = [
  'Bundle upload',
  'Publisher grants',
  'Mod grants',
  'Kick / ban actions',
  'Games-realm role sync',
];

/**
 * A5 Settings — path 'settings'. The one runtime toggle the BFF exposes
 * (GET /games/api/admin/debug-mode, PUT .../debug-mode/wasm-console) and an
 * honest list of what is managed in the standalone games portal instead.
 */
@Component({
  selector: 'hug-games-admin-settings',
  standalone: true,
  imports: [GamesAdminForbiddenComponent, GamesUnreachableComponent],
  template: `
    <section class="settings" data-testid="games-admin-settings" aria-labelledby="games-admin-settings-title">
      <h2 id="games-admin-settings-title">Settings</h2>

      @switch (state()) {
        @case ('loading') {
          <p class="hg-panel" role="status" aria-busy="true"><span class="hg-spinner" aria-hidden="true"></span>Loading debug mode…</p>
        }
        @case ('forbidden') {
          <hug-games-admin-forbidden [failure]="failure()"></hug-games-admin-forbidden>
        }
        @case ('unreachable') {
          <hug-games-unreachable (retry)="load()"></hug-games-unreachable>
        }
        @case ('loaded') {
          <div class="hg-panel toggle-row">
            <label class="toggle">
              <input
                type="checkbox"
                [checked]="wasmConsole()"
                [disabled]="saving()"
                (change)="setWasmConsole($event)"
                data-testid="games-admin-wasm-console"
              />
              <span>Forward WASM console output</span>
            </label>
            <p class="hg-muted">
              When on, every game bundle's console output is forwarded to the BFF's diagnostic
              capture. Admin-only, off by default.
            </p>
            @if (saveError()) {
              <p class="hg-notice hg-notice--error" role="alert" data-testid="games-admin-settings-error">{{ saveError() }}</p>
            }
          </div>
        }
      }

      <div class="hg-panel" data-testid="games-admin-not-here">
        <h3>Not available on this mount</h3>
        <p class="hg-muted">
          These are managed in the standalone games portal, not from the mesh shell:
        </p>
        <ul>
          @for (item of notHere; track item) {
            <li>{{ item }} — managed in the standalone games portal</li>
          }
        </ul>
      </div>
    </section>
  `,
  styles: [
    `
      .settings {
        display: grid;
        gap: 1rem;
      }
      h2,
      h3 {
        margin: 0;
      }
      .toggle-row {
        display: grid;
        gap: 0.5rem;
      }
      .toggle {
        display: inline-flex;
        align-items: center;
        gap: 0.5rem;
        min-height: 2.5rem;
        font-weight: 600;
      }
      .toggle input {
        width: 1.2rem;
        height: 1.2rem;
      }
      p {
        margin: 0;
      }
      ul {
        margin: 0.5rem 0 0;
        padding-left: 1.2rem;
      }
    `,
  ],
})
export class GamesAdminSettingsComponent implements OnInit {
  private readonly api = inject(GamesApiService);

  readonly notHere = NOT_ON_THIS_MOUNT;
  readonly state = signal<'loading' | 'loaded' | 'forbidden' | 'unreachable'>('loading');
  readonly failure = signal<ApiFailure | null>(null);
  readonly wasmConsole = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    this.failure.set(null);
    this.api.debugMode().subscribe({
      next: (s) => {
        this.wasmConsole.set(!!s?.wasmConsoleEnabled);
        this.state.set('loaded');
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        this.state.set(f.kind === 'forbidden' ? 'forbidden' : 'unreachable');
      },
    });
  }

  setWasmConsole(ev: Event): void {
    const enabled = (ev.target as HTMLInputElement).checked;
    this.saving.set(true);
    this.saveError.set(null);
    this.api.setWasmConsole(enabled).subscribe({
      next: (s) => {
        this.wasmConsole.set(!!s?.wasmConsoleEnabled);
        this.saving.set(false);
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.saveError.set(
          f.kind === 'forbidden'
            ? `The gateway refused the change (${f.status}).`
            : `Could not save the setting (${f.status || 'network'}).`,
        );
        this.saving.set(false);
      },
    });
  }
}
