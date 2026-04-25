import { Component, EventEmitter, Input, Output } from '@angular/core';

/**
 * Federated admin shell. A thin frame the host can drop in once a
 * user with ROLE_ADMIN navigates to /admin. The host injects:
 *   - actions:   list of admin pills the host wants to expose
 *                  (uploads, role overrides, federation manifest, …)
 *   - characterCount / federationCount: counters the host already
 *                                       fetched, so the remote stays
 *                                       data-source agnostic.
 *
 * Bound HTTP calls (e.g. /api/admin/games/upload) stay on the host —
 * the remote emits user intent through (action) and the host's
 * UploadService / FederationService dispatches.
 */
@Component({
  selector: 'app-admin-remote',
  standalone: true,
  template: `
    <section class="admin-shell">
      <header>
        <h1 class="admin-title">Admin Console</h1>
        <p class="admin-sub">
          Federated remote · <code>admin</code> · {{ characterCount }} characters,
          {{ federationCount }} federation modules
        </p>
      </header>

      <div class="admin-grid">
        @for (act of actions; track act.id) {
          <button class="admin-pill" (click)="action.emit(act.id)">
            <span class="pill-icon">{{ act.icon }}</span>
            <span class="pill-label">{{ act.label }}</span>
          </button>
        }
        @if (actions.length === 0) {
          <p class="admin-empty">
            No admin actions wired yet. The host populates the
            <code>actions</code> input — leave the federation manifest
            and the upload UI in the host until each gets its own remote.
          </p>
        }
      </div>
    </section>
  `,
  styles: [`
    :host { display: block; padding: 24px; color: #d8dde6; font-family: sans-serif; }
    .admin-shell { background: #131625; border: 1px solid #232a3d; border-radius: 10px;
                   padding: 24px; }
    .admin-title { margin: 0 0 4px; font-size: 22px; }
    .admin-sub { margin: 0 0 16px; color: #8b94a8; font-size: 12px; }
    .admin-sub code { background: #1a1d28; padding: 1px 4px; border-radius: 3px; }
    .admin-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
                  gap: 10px; }
    .admin-pill { background: #1a1d28; border: 1px solid #2a3148; border-radius: 8px;
                  color: inherit; cursor: pointer; padding: 14px;
                  display: flex; flex-direction: column; gap: 6px; align-items: flex-start;
                  font: inherit; transition: transform .12s ease, border-color .12s ease; }
    .admin-pill:hover { transform: translateY(-1px); border-color: #6c63ff; }
    .pill-icon { font-size: 20px; }
    .pill-label { font-size: 13px; font-weight: 600; }
    .admin-empty { color: #5a6274; font-size: 12px; margin: 0; }
    .admin-empty code { background: #1a1d28; padding: 1px 4px; border-radius: 3px; }
  `],
})
export class AdminRemoteComponent {
  @Input() actions: ReadonlyArray<{ id: string; icon: string; label: string }> = [];
  @Input() characterCount = 0;
  @Input() federationCount = 0;

  /** Emits the action id the user clicked; host owns dispatch. */
  @Output() readonly action = new EventEmitter<string>();
}
