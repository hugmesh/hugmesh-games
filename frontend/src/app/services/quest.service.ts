import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { CharacterQuest, Quest } from '../models/quest.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class QuestService {
  private readonly http = inject(HttpClient);

  getCharacterQuests(characterId: number = 1): Observable<CharacterQuest[]> {
    return this.http.get<CharacterQuest[]>(
      `${environment.apiBase}/api/characters/${characterId}/quests`
    );
  }

  acceptQuest(questId: number, characterId: number = 1): Observable<CharacterQuest> {
    return this.http.put<CharacterQuest>(
      `${environment.apiBase}/api/characters/${characterId}/quests/${questId}`,
      {}
    );
  }

  getAvailableQuests(): Observable<Quest[]> {
    return this.http.get<Quest[]>(`${environment.apiBase}/api/quests`);
  }

  /** Marks a quest complete for the given character; may unlock dependent levels. */
  completeQuest(questId: number, characterId: number = 1): Observable<CharacterQuest> {
    return this.http.put<CharacterQuest>(
      `${environment.apiBase}/api/characters/${characterId}/quests/${questId}/complete`,
      {}
    );
  }
}
