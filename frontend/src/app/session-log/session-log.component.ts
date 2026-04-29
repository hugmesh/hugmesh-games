import { Component, Input, OnChanges, OnInit, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SessionLogEntry, SessionLogService } from '../services/session-log.service';

/** Which scope is read from the BFF.
 *  - {@code 'me'}     calls /me — caller's own history.
 *  - {@code 'admin'}  calls /admin/{casdoor} — admin-only.
 *  - {@code 'scoped'} calls /scoped/{casdoor} — mod/publisher games only.
 */
export type SessionLogScope = 'me' | 'admin' | 'scoped';

/**
 * Renders a sortable table of session-membership rows. Reusable across
 * the player dashboard, the admin user-detail panel, and the
 * mod/publisher per-user view via the {@code scope} input.
 */
@Component({
  selector: 'app-session-log',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './session-log.component.html',
  styleUrl: './session-log.component.scss',
})
export class SessionLogComponent implements OnInit, OnChanges {
  private readonly svc = inject(SessionLogService);

  @Input() scope: SessionLogScope = 'me';
  /** Required when scope is 'admin' or 'scoped'. */
  @Input() casdoorId: string | null = null;
  @Input() limit = 50;

  readonly rows = signal<SessionLogEntry[]>([]);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  ngOnInit(): void { this.refresh(); }
  ngOnChanges(): void { this.refresh(); }

  refresh(): void {
    this.loading.set(true);
    this.error.set(null);
    const obs =
      this.scope === 'me'                     ? this.svc.me(this.limit) :
      this.scope === 'admin' && this.casdoorId ? this.svc.admin(this.casdoorId, this.limit) :
      this.scope === 'scoped' && this.casdoorId ? this.svc.scoped(this.casdoorId, this.limit) :
      null;
    if (!obs) {
      this.error.set('A casdoor id is required for that scope.');
      this.loading.set(false);
      return;
    }
    obs.subscribe({
      next: rows => { this.rows.set(rows); this.loading.set(false); },
      error: () => { this.rows.set([]); this.loading.set(false); /* toast surfaced by interceptor */ },
    });
  }

  /** Friendly duration: "12 min", "1 h 4 min". Open sessions show "in progress". */
  duration(r: SessionLogEntry): string {
    const start = Date.parse(r.joinedAt);
    const end = r.leftAt ? Date.parse(r.leftAt) :
                r.sessionClosedAt ? Date.parse(r.sessionClosedAt) : null;
    if (end == null) return 'in progress';
    const minutes = Math.max(0, Math.round((end - start) / 60_000));
    if (minutes < 60) return `${minutes} min`;
    const hours = Math.floor(minutes / 60);
    return `${hours} h ${minutes % 60} min`;
  }
}
