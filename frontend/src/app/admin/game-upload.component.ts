import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

import {
  AccessScope,
  GameUploadHistory,
  GameUploadService,
  InstalledUpload,
  RoleSummary,
} from './game-upload.service';

/**
 * Admin console panel for hot-uploading a Snapdragon bundle zip and
 * managing per-game access policy. The zip is the artefact produced by
 * the Godot editor plugin (`Project → Tools → Snapdragon → Export…`).
 *
 * Auth: route is gated on isAdmin in app.routes.ts. The HTTP requests
 * carry the JWT via http-auth.interceptor, which the BFF re-checks
 * with @PreAuthorize("hasRole('ADMIN')").
 */
@Component({
  selector: 'app-game-upload',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './game-upload.component.html',
  styleUrl: './game-upload.component.scss',
})
export class GameUploadComponent {
  private readonly svc = inject(GameUploadService);

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
    this.svc.roles().subscribe({
      next: r => this.roles.set(r),
      error: () => this.roles.set([]),
    });
  }

  onDragOver(ev: DragEvent): void {
    ev.preventDefault();
    this.isDragging.set(true);
  }
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
    if (!this.selectedFile) { return; }
    this.uploadStatus.set('uploading');
    this.uploadError.set('');
    this.svc.upload(this.selectedFile).subscribe({
      next: r => {
        this.uploadResult.set(r);
        this.uploadStatus.set('success');
      },
      error: err => {
        this.uploadError.set(err?.error?.message ?? err?.message ?? 'upload failed');
        this.uploadStatus.set('error');
      },
    });
  }

  loadHistory(): void {
    if (!this.historySlug) return;
    this.svc.history(this.historySlug).subscribe({
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
    this.svc.grantAccess(this.accessSlug, {
      scope: this.accessScope,
      value: this.accessScope === 'all' ? undefined : this.accessValue,
    }).subscribe({
      next: () => this.accessStatus.set(`✓ granted ${this.accessScope}${this.accessValue ? ':' + this.accessValue : ''}`),
      error: err => this.accessStatus.set(`error: ${err?.error?.message ?? err?.message}`),
    });
  }

  revoke(): void {
    if (!this.accessSlug) return;
    this.accessStatus.set('revoking…');
    this.svc.revokeAccess(this.accessSlug, this.accessScope,
                          this.accessScope === 'all' ? undefined : this.accessValue).subscribe({
      next: () => this.accessStatus.set(`✓ revoked ${this.accessScope}${this.accessValue ? ':' + this.accessValue : ''}`),
      error: err => this.accessStatus.set(`error: ${err?.error?.message ?? err?.message}`),
    });
  }
}
