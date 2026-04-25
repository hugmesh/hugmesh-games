import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { environment } from '../../environments/environment';

export interface InstalledUpload {
  slug: string;
  contentHash: string;
  versionDir: string;
  uploadedAt: string;
}

export interface GameUploadHistory {
  id: number;
  slug: string;
  versionDir: string;
  contentHash: string;
  manifestJson: string;
  uploadedBy: string;
  uploadedAt: string;
  supersededAt: string | null;
}

export interface RoleSummary {
  name: string;
  displayName: string;
  description: string;
  enabled: boolean;
}

export type AccessScope = 'all' | 'role' | 'user';
export interface AccessGrantRequest { scope: AccessScope; value?: string; }

@Injectable({ providedIn: 'root' })
export class GameUploadService {
  private readonly http = inject(HttpClient);
  private readonly base = environment.apiBase;

  upload(zip: File): Observable<InstalledUpload> {
    const form = new FormData();
    form.append('bundle', zip, zip.name);
    return this.http.post<InstalledUpload>(`${this.base}/api/admin/games/upload`, form);
  }

  history(slug: string): Observable<GameUploadHistory[]> {
    return this.http.get<GameUploadHistory[]>(`${this.base}/api/admin/games/${slug}/uploads`);
  }

  roles(): Observable<RoleSummary[]> {
    return this.http.get<RoleSummary[]>(`${this.base}/api/admin/roles`);
  }

  grantAccess(slug: string, body: AccessGrantRequest): Observable<unknown> {
    return this.http.post(`${this.base}/api/admin/games/${slug}/access`, body);
  }

  revokeAccess(slug: string, scope: AccessScope, value?: string): Observable<unknown> {
    const params = new URLSearchParams({ scope });
    if (value) params.set('value', value);
    return this.http.delete(`${this.base}/api/admin/games/${slug}/access?${params}`);
  }
}
