import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface Relationship {
  id: number;
  userCasdoorId: string;
  otherCasdoorId: string;
  relationship: 'friend_request' | 'friend' | 'blocked';
  createdAt: string;
  updatedAt: string;
}

/**
 * Wraps `/api/relationships/*` — the friend graph + blocklist surface
 * managed by `UserRelationshipController`.
 *
 * <p>The caller's own casdoor id is read off the JWT subject on the
 * server; clients only ever supply the {@code other} target.
 */
@Injectable({ providedIn: 'root' })
export class FriendsService {
  private http = inject(HttpClient);

  friends():  Observable<Relationship[]> { return this.http.get<Relationship[]>('/api/relationships/friends'); }
  incoming(): Observable<Relationship[]> { return this.http.get<Relationship[]>('/api/relationships/friends/incoming'); }
  outgoing(): Observable<Relationship[]> { return this.http.get<Relationship[]>('/api/relationships/friends/outgoing'); }
  blocks():   Observable<Relationship[]> { return this.http.get<Relationship[]>('/api/relationships/blocks'); }

  request(other: string): Observable<Relationship> {
    return this.http.post<Relationship>('/api/relationships/friends/request', { other });
  }
  accept(other: string): Observable<Relationship> {
    return this.http.post<Relationship>('/api/relationships/friends/accept', { other });
  }
  remove(other: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>('/api/relationships/friends/remove', { other });
  }
  block(other: string): Observable<Relationship> {
    return this.http.post<Relationship>('/api/relationships/blocks/add', { other });
  }
  unblock(other: string): Observable<{ ok: boolean }> {
    return this.http.post<{ ok: boolean }>('/api/relationships/blocks/remove', { other });
  }
}
