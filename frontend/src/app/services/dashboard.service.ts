import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Dashboard } from '../models/dashboard.model';
import { SessionHistory } from '../models/session-history.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  getDashboard(characterId: number): Observable<Dashboard> {
    return this.http.get<Dashboard>(`${environment.apiBase}/api/characters/${characterId}/dashboard`);
  }

  getSessionHistory(): Observable<SessionHistory[]> {
    return this.http.get<SessionHistory[]>(`${environment.apiBase}/api/sessions/history`);
  }
}
