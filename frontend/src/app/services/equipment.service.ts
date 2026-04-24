import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { EquipmentSlot, Item } from '../models/equipment.model';
import { environment } from '../../environments/environment';

@Injectable({ providedIn: 'root' })
export class EquipmentService {
  private readonly http = inject(HttpClient);

  getEquipment(characterId: number = 1): Observable<EquipmentSlot[]> {
    return this.http.get<EquipmentSlot[]>(
      `${environment.apiBase}/api/characters/${characterId}/equipment`
    );
  }

  getItems(): Observable<Item[]> {
    return this.http.get<Item[]>(`${environment.apiBase}/api/items`);
  }

  equip(slot: string, itemId: number, characterId: number = 1): Observable<EquipmentSlot[]> {
    return this.http.put<EquipmentSlot[]>(
      `${environment.apiBase}/api/characters/${characterId}/equipment/${slot}`,
      { itemId }
    );
  }
}
