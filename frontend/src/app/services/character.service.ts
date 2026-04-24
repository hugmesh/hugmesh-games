import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { BehaviorSubject, Observable } from 'rxjs';
import { tap } from 'rxjs/operators';
import { CharacterState } from '../models/character.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class CharacterService {
  private readonly http = inject(HttpClient);

  private readonly _character$ = new BehaviorSubject<CharacterState | null>(null);
  readonly character$ = this._character$.asObservable();

  load(id: number = 1): Observable<CharacterState> {
    return this.http.get<CharacterState>(`${environment.apiBase}/api/characters/${id}`).pipe(
      tap(c => this._character$.next(c))
    );
  }

  update(id: number, patch: Partial<CharacterState>): Observable<CharacterState> {
    return this.http.put<CharacterState>(`${environment.apiBase}/api/characters/${id}`, patch).pipe(
      tap(c => this._character$.next(c))
    );
  }
}
