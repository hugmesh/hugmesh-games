import { Component, OnInit, inject, signal, ChangeDetectionStrategy } from '@angular/core';

import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

// API DTO shapes — mirror the host's deleted game-upload.service exports.
// TypeScript types are erased at runtime; federation can't share them,
// so the BFF JSON contract is the source of truth on both sides.
interface InstalledUpload {
  slug: string;
  contentHash: string;
  versionDir: string;
  uploadedAt: string;
}
interface GameUploadHistory {
  id: number;
  slug: string;
  versionDir: string;
  contentHash: string;
  manifestJson: string;
  uploadedBy: string;
  uploadedAt: string;
  supersededAt: string | null;
}
interface RoleSummary {
  name: string;
  displayName: string;
  description: string;
  enabled: boolean;
}
type AccessScope = 'all' | 'role' | 'user';
interface AccessGrantRequest { scope: AccessScope; value?: string; }

/**
 * Federated game-upload + per-game access shell. Loaded by the host's
 * `/admin/games` route via
 *
 *   loadComponent: () => loadRemoteModule({ remoteName: 'admin',
 *                          exposedModule: './UploadShell' })
 *                          .then(m => m.UploadRemoteComponent)
 *
 * Same admin image as AdminShell — a single nginx serves both bundles.
 *
 * Why HttpClient is the only injected dep: the host's old
 * GameUploadService was a thin wrapper around the same calls
 * (POST /api/admin/games/upload, GET /api/admin/games/{slug}/uploads,
 *  GET /api/admin/roles, POST/DELETE /api/admin/games/{slug}/access).
 * Inlining the calls keeps the HTTP interceptor (Bearer JWT) on the
 * critical path while removing one layer that has to stay in sync
 * with the BFF.
 */
@Component({
  selector: 'app-admin-upload-remote',
  standalone: true,
  imports: [FormsModule],
  template: `
    <section class="upload-page">
      <h2>Upload a game bundle</h2>
      <p class="hint">
        Drag a <code>&lt;slug&gt;-snapdragon-bundle.zip</code> produced by
        <em>Project → Tools → Snapdragon → Export to Snapdragon…</em> in the
        Godot editor. The BFF validates <code>manifest.json</code> (schema_version 2),
        runs <code>register.sql</code> in a transaction, and atomically swaps the
        asset version dir without disturbing live sessions.
        <br>
        <span class="federated-source">Federated remote · <code>admin/UploadShell</code></span>
      </p>

      <div class="dropzone"
           [class.dragging]="isDragging()"
           (dragover)="onDragOver($event)"
           (dragleave)="onDragLeave()"
           (drop)="onDrop($event)">
        @if (selectedFile) {
          <p>📦 {{ selectedFile.name }} ({{ (selectedFile.size / 1024 / 1024).toFixed(1) }} MB)</p>
        } @else {
          <p>Drop a bundle zip here or</p>
        }
        <input type="file" accept=".zip"
               aria-label="Upload Snapdragon bundle zip"
               (change)="onFilePicked($event)">
      </div>

      <button class="primary"
              (click)="upload()"
              [disabled]="!selectedFile || uploadStatus() === 'uploading'">
        @if (uploadStatus() === 'uploading') { Uploading… } @else { Install bundle }
      </button>

      @if (uploadStatus() === 'success' && uploadResult()) {
        <div class="result success" role="status">
          ✓ Installed <code>{{ uploadResult()!.slug }}</code>
          &#64; <code>{{ uploadResult()!.contentHash.substring(0, 12) }}…</code>
          ({{ uploadResult()!.uploadedAt }})
        </div>
      }
      @if (uploadStatus() === 'error') {
        <div class="result error" role="alert" aria-live="assertive">✗ {{ uploadError() }}</div>
      }
    </section>

    <section class="history-page">
      <h2>Upload history</h2>
      <div class="row">
        <input [(ngModel)]="historySlug"
               aria-label="Game slug for upload history lookup"
               placeholder="game slug (e.g. dodecahedron)">
        <button (click)="loadHistory()">Load</button>
      </div>
      @if (history().length) {
        <table>
          <thead>
            <tr><th>uploaded_at</th><th>hash</th><th>by</th><th>active</th></tr>
          </thead>
          <tbody>
            @for (row of history(); track row.id) {
              <tr [class.superseded]="row.supersededAt">
                <td>{{ row.uploadedAt }}</td>
                <td><code>{{ row.contentHash.substring(0, 12) }}…</code></td>
                <td>{{ row.uploadedBy }}</td>
                <td>{{ row.supersededAt ? 'no (superseded)' : 'yes' }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>

    <section class="access-page">
      <h2>Per-game access</h2>
      <p class="hint">
        Layered on top of quest-based level unlocks. Grant access to
        <strong>everyone</strong>, a <strong>Casdoor role</strong> (the role
        catalog comes from <code>/api/admin/roles</code>), or a single
        <strong>Casdoor user id</strong>. Multiple grants are additive (union).
      </p>

      <div class="form">
        <label>game slug
          <input [(ngModel)]="accessSlug" placeholder="three-wizards">
        </label>
        <label>scope
          <select [(ngModel)]="accessScope">
            <option value="all">all (every authenticated user)</option>
            <option value="role">role (Casdoor role)</option>
            <option value="user">user (Casdoor sub-claim)</option>
          </select>
        </label>
        @if (accessScope === 'role') {
          <label>role
            <select [(ngModel)]="accessValue">
              <option value="">— pick a role —</option>
              @for (r of roles(); track r.name) {
                <option [value]="r.name">{{ r.name }} — {{ r.displayName }}</option>
              }
            </select>
          </label>
        }
        @if (accessScope === 'user') {
          <label>casdoor user id
            <input [(ngModel)]="accessValue" placeholder="8257e529-914a-43ba-a212-…">
          </label>
        }
        <div class="actions">
          <button class="primary" (click)="grant()">Grant</button>
          <button (click)="revoke()">Revoke</button>
        </div>
        @if (accessStatus()) {
          <p class="status" role="status">{{ accessStatus() }}</p>
        }
      </div>
    </section>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    :host { display: block; max-width: 960px; margin: 24px auto; padding: 0 16px; color: #d8dde6; }
    section { margin-bottom: 32px; padding: 20px; background: #14172185; border-radius: 8px; }
    h2 { margin: 0 0 8px; color: #cfd5e0; }
    .hint { font-size: 13px; color: #8b94a8; margin: 0 0 16px; line-height: 1.5; }
    .hint code { background: #0f111a; padding: 1px 5px; border-radius: 3px; }
    .federated-source { display: inline-block; margin-top: 6px; font-size: 11px; opacity: 0.8; }
    .dropzone { border: 2px dashed #3a3f55; border-radius: 6px; padding: 28px;
                text-align: center; background: #0f111a; margin-bottom: 12px; }
    .dropzone.dragging { border-color: #8a6cdc; background: #1a1530; }
    .dropzone p { margin: 0 0 8px; color: #8b94a8; }
    .row, .form { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; margin-bottom: 12px; }
    .form label { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: #8b94a8; }
    input, select { background: #0f111a; color: #d8dde6; border: 1px solid #2a2f42;
                    border-radius: 4px; padding: 6px 10px; min-width: 220px; }
    button { background: #2a2f42; color: #d8dde6; border: 1px solid #3a3f55;
              border-radius: 4px; padding: 8px 16px; cursor: pointer; }
    button:hover { background: #353b50; }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
    button.primary { background: #8a6cdc; border-color: #8a6cdc; color: #fff; }
    button.primary:hover { background: #9577ee; }
    .result { margin-top: 12px; padding: 10px 14px; border-radius: 4px; font-family: monospace; font-size: 13px; }
    .result.success { background: #1a3a1a; color: #6fce6f; border: 1px solid #2d5a2d; }
    .result.error   { background: #3a1a1a; color: #ce6f6f; border: 1px solid #5a2d2d; }
    .status { margin: 8px 0 0; color: #b8a574; font-size: 13px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    table th, table td { padding: 6px 10px; text-align: left; border-bottom: 1px solid #2a2f42; }
    table tr.superseded { opacity: 0.55; }
    .actions { display: flex; gap: 8px; }
  `],
})
export class UploadRemoteComponent implements OnInit {
  private readonly http = inject(HttpClient);

  selectedFile: File | null = null;
  isDragging = signal(false);
  uploadStatus = signal<'idle' | 'uploading' | 'success' | 'error'>('idle');
  uploadResult = signal<InstalledUpload | null>(null);
  uploadError = signal<string>('');

  historySlug = '';
  history = signal<GameUploadHistory[]>([]);

  accessSlug = '';
  accessScope: AccessScope = 'all';
  accessValue = '';
  accessStatus = signal<string>('');

  roles = signal<RoleSummary[]>([]);

  ngOnInit(): void {
    this.http.get<RoleSummary[]>('/api/admin/roles').subscribe({
      next: r => this.roles.set(r),
      error: () => this.roles.set([]),
    });
  }

  onDragOver(ev: DragEvent): void { ev.preventDefault(); this.isDragging.set(true); }
  onDragLeave(): void { this.isDragging.set(false); }
  onDrop(ev: DragEvent): void {
    ev.preventDefault();
    this.isDragging.set(false);
    const f = ev.dataTransfer?.files?.[0];
    if (f) this.selectedFile = f;
  }
  onFilePicked(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    this.selectedFile = input.files?.[0] ?? null;
  }

  upload(): void {
    if (!this.selectedFile) return;
    this.uploadStatus.set('uploading');
    this.uploadError.set('');

    const form = new FormData();
    form.append('bundle', this.selectedFile, this.selectedFile.name);

    this.http.post<InstalledUpload>('/api/admin/games/upload', form).subscribe({
      next: r => { this.uploadResult.set(r); this.uploadStatus.set('success'); },
      error: err => {
        this.uploadError.set(err?.error?.message ?? err?.message ?? 'upload failed');
        this.uploadStatus.set('error');
      },
    });
  }

  loadHistory(): void {
    if (!this.historySlug) return;
    this.http.get<GameUploadHistory[]>(`/api/admin/games/${this.historySlug}/uploads`).subscribe({
      next: h => this.history.set(h),
      error: () => this.history.set([]),
    });
  }

  grant(): void {
    if (!this.accessSlug) return;
    if (this.accessScope !== 'all' && !this.accessValue) {
      this.accessStatus.set('value required for role/user scope');
      return;
    }
    this.accessStatus.set('granting…');
    const body: AccessGrantRequest = {
      scope: this.accessScope,
      value: this.accessScope === 'all' ? undefined : this.accessValue,
    };
    this.http.post(`/api/admin/games/${this.accessSlug}/access`, body).subscribe({
      next: () => this.accessStatus.set(`✓ granted ${this.accessScope}${this.accessValue ? ':' + this.accessValue : ''}`),
      error: err => this.accessStatus.set(`error: ${err?.error?.message ?? err?.message}`),
    });
  }

  revoke(): void {
    if (!this.accessSlug) return;
    this.accessStatus.set('revoking…');
    const params = new URLSearchParams({ scope: this.accessScope });
    if (this.accessScope !== 'all' && this.accessValue) params.set('value', this.accessValue);
    this.http.delete(`/api/admin/games/${this.accessSlug}/access?${params}`).subscribe({
      next: () => this.accessStatus.set(`✓ revoked ${this.accessScope}${this.accessValue ? ':' + this.accessValue : ''}`),
      error: err => this.accessStatus.set(`error: ${err?.error?.message ?? err?.message}`),
    });
  }
}
