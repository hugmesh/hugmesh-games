import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { Skill, SkillSlots } from '../models/skill.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class SkillService {
  private readonly http = inject(HttpClient);

  getLearnedSkills(characterId: number = 1): Observable<Skill[]> {
    return this.http.get<Skill[]>(
      `${environment.apiBase}/api/characters/${characterId}/skills`
    );
  }

  getSkillSlots(characterId: number = 1): Observable<SkillSlots> {
    return this.http.get<SkillSlots>(
      `${environment.apiBase}/api/characters/${characterId}/skill-slots`
    );
  }

  assignSkill(slot: number, skillId: number, characterId: number = 1): Observable<SkillSlots> {
    return this.http.put<SkillSlots>(
      `${environment.apiBase}/api/characters/${characterId}/skill-slots/${slot}`,
      { skillId }
    );
  }
}
