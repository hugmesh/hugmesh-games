import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

interface PublisherGameScope {
  casdoorId: string;
  gameSlug: string;
  grantedByCasdoorId: string;
  grantedAt: string;
}
interface ModGameScope {
  casdoorId: string;
  gameSlug: string;
  grantedByCasdoorId: string;
  grantedAt: string;
}

/**
 * Federated publisher shell. Loaded by the host's `/publisher` route.
 *
 * Three cards:
 *   1. Owned games — list of publisher_game_scopes the caller has.
 *   2. Upload a bundle — multipart POST to /api/admin/games/upload
 *      (the route accepts ROLE_PUBLISHER as well as ROLE_ADMIN).
 *   3. Manage moderators — for a chosen owned-game slug, list active
 *      mods and grant / revoke the mod role for new users.
 *
 * Authentication: ROLE_PUBLISHER or ROLE_ADMIN (BFF `@PreAuthorize`).
 */
@Component({
  selector: 'app-publisher-remote',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="publisher-page">
      <header class="publisher-header">
        <h1>&#9670; Publisher console</h1>
        <button class="btn" (click)="refresh()">&#8635; Refresh</button>
      </header>
      @if (errorMessage) { <p class="error-banner">{{ errorMessage }}</p> }
      @if (successMessage) { <p class="success-banner">{{ successMessage }}</p> }

      <section class="card">
        <h2>Games you publish</h2>
        @if (scopes$ | async; as scopes) {
          @if (scopes.length === 0) {
            <p class="muted">No publisher scopes assigned. Ask an admin to grant via <code>/api/admin/role-grants/grant-publisher</code>.</p>
          } @else {
            <ul class="scope-list">
              @for (s of scopes; track s.gameSlug) {
                <li>
                  <button class="link-btn" type="button"
                          [class.active]="selectedSlug === s.gameSlug"
                          (click)="selectGame(s.gameSlug)">{{ s.gameSlug }}</button>
                  &mdash; granted {{ s.grantedAt | date:'short' }}
                </li>
              }
            </ul>
          }
        }
      </section>

      <section class="card">
        <h2>Upload a bundle</h2>
        <p class="muted">
          Multipart POST to <code>/api/admin/games/upload</code>. The
          manifest's <code>game.slug</code> must be one you publish.
        </p>
        <input type="file" (change)="pick($event)" accept=".zip" />
        <button class="primary-btn" type="button"
                [disabled]="!pickedFile" (click)="upload()">Upload</button>
      </section>

      <section class="card">
        <h2>Moderators for {{ selectedSlug || '(select a game above)' }}</h2>
        @if (selectedSlug) {
          @if (mods$ | async; as mods) {
            <ul class="mod-list">
              @for (m of mods; track m.casdoorId) {
                <li>
                  {{ m.casdoorId }} &mdash; granted by {{ m.grantedByCasdoorId }}
                  <button type="button" class="link-btn revoke"
                          (click)="revokeMod(m.casdoorId)">Revoke</button>
                </li>
              }
            </ul>
          }
          <div class="grant-row">
            <input [(ngModel)]="grantTarget" placeholder="casdoor user id" />
            <button class="primary-btn" type="button"
                    [disabled]="!grantTarget" (click)="grantMod()">Grant mod</button>
          </div>
        }
      </section>
    </div>
  `,
  styles: [`
    .publisher-page { padding: 1.5rem; }
    .publisher-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    h1 { margin: 0; font-size: 1.4rem; }
    .card { background: #ecf3ff; border: 1px solid #8aa8d8; border-radius: 6px; padding: 1rem; margin-bottom: 1rem; }
    .scope-list, .mod-list { list-style: none; padding-left: 0; }
    .link-btn { background: none; border: none; color: #20448c; cursor: pointer; padding: 0; text-decoration: underline; }
    .link-btn.active { font-weight: 600; }
    .link-btn.revoke { color: #8b1f1f; margin-left: 0.5rem; }
    .primary-btn, .btn { padding: 0.4rem 0.9rem; border-radius: 4px; border: 1px solid #20448c; background: #cfe0ff; cursor: pointer; margin-left: 0.4rem; }
    .primary-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .grant-row { margin-top: 0.6rem; display: flex; gap: 0.4rem; align-items: center; }
    .grant-row input { padding: 0.3rem; border-radius: 3px; border: 1px solid #aabddf; min-width: 18ch; }
    .muted { color: #5a6a8a; font-style: italic; }
    .error-banner { background: #f9d5d5; color: #8b1f1f; padding: 0.6rem 1rem; border-radius: 4px; margin-bottom: 1rem; }
    .success-banner { background: #d5f9e0; color: #1f6b3a; padding: 0.6rem 1rem; border-radius: 4px; margin-bottom: 1rem; }
  `],
})
export class PublisherRemoteComponent implements OnInit {
  private http = inject(HttpClient);
  scopes$: Observable<PublisherGameScope[]> = of([]);
  mods$: Observable<ModGameScope[]> = of([]);
  errorMessage = '';
  successMessage = '';
  selectedSlug = '';
  grantTarget = '';
  pickedFile: File | null = null;

  ngOnInit(): void { this.refresh(); }

  refresh(): void {
    this.errorMessage = ''; this.successMessage = '';
    this.scopes$ = this.http.get<PublisherGameScope[]>('/api/publisher/scopes').pipe(
      catchError(err => {
        this.errorMessage = err?.status === 403
          ? 'You need the publisher or admin role.'
          : `Failed to load scopes: ${err?.message ?? err}`;
        return of([]);
      })
    );
    if (this.selectedSlug) this.refreshMods();
  }

  selectGame(slug: string): void {
    this.selectedSlug = slug;
    this.refreshMods();
  }

  refreshMods(): void {
    this.mods$ = this.http.get<ModGameScope[]>(
      `/api/publisher/games/${this.selectedSlug}/mods`).pipe(catchError(() => of([])));
  }

  pick(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    this.pickedFile = (input.files && input.files[0]) || null;
  }

  upload(): void {
    if (!this.pickedFile) return;
    const fd = new FormData();
    fd.append('bundle', this.pickedFile, this.pickedFile.name);
    this.http.post('/api/admin/games/upload', fd).subscribe({
      next: () => { this.successMessage = 'Bundle uploaded.'; this.refresh(); },
      error: e => this.errorMessage = `Upload failed: ${e?.error?.message ?? e?.message ?? e}`,
    });
  }

  grantMod(): void {
    if (!this.grantTarget || !this.selectedSlug) return;
    this.http.post('/api/publisher/grant-mod', {
      targetCasdoorId: this.grantTarget, gameSlug: this.selectedSlug,
    }).subscribe({
      next: () => { this.grantTarget = ''; this.successMessage = 'Mod scope granted.'; this.refreshMods(); },
      error: e => this.errorMessage = `Grant failed: ${e?.error?.message ?? e?.message ?? e}`,
    });
  }

  revokeMod(target: string): void {
    if (!confirm(`Revoke mod scope from ${target}?`)) return;
    this.http.post('/api/publisher/revoke-mod', {
      targetCasdoorId: target, gameSlug: this.selectedSlug,
    }).subscribe({
      next: () => { this.successMessage = 'Mod scope revoked.'; this.refreshMods(); },
      error: e => this.errorMessage = `Revoke failed: ${e?.error?.message ?? e?.message ?? e}`,
    });
  }
}
