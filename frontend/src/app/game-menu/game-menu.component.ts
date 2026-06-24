import {
  Component,
  EventEmitter,
  HostListener,
  Input,
  OnInit,
  Output,
  ChangeDetectionStrategy
} from '@angular/core';

import { QuestService } from '../services/quest.service';
import { SkillService } from '../services/skill.service';
import { EquipmentService } from '../services/equipment.service';
import { CharacterQuest, Quest } from '../models/quest.model';
import { Skill, SkillSlots } from '../models/skill.model';
import { EquipmentSlot } from '../models/equipment.model';
import { catchError, of } from 'rxjs';

type MenuTab = 'QUESTS' | 'EQUIPMENT' | 'SKILLS';

const EQUIPMENT_SLOTS = ['head', 'chest', 'legs', 'feet', 'weapon', 'offhand', 'ring1', 'ring2'];

@Component({
  selector: 'app-game-menu',
  standalone: true,
  imports: [],
  templateUrl: './game-menu.component.html',
  changeDetection: ChangeDetectionStrategy.Eager,
  styleUrl: './game-menu.component.scss',
})
export class GameMenuComponent implements OnInit {
  @Input() isOpen = false;
  @Output() closed = new EventEmitter<void>();

  activeTab: MenuTab = 'QUESTS';
  tabs: MenuTab[] = ['QUESTS', 'EQUIPMENT', 'SKILLS'];

  characterQuests: CharacterQuest[] = [];
  availableQuests: Quest[] = [];
  learnedSkills: Skill[] = [];
  skillSlots: SkillSlots = {};
  equipmentSlots: EquipmentSlot[] = [];
  allEquipmentSlots = EQUIPMENT_SLOTS;

  selectedItem: EquipmentSlot | null = null;
  pendingSkillAssign: Skill | null = null;

  constructor(
    private questService: QuestService,
    private skillService: SkillService,
    private equipmentService: EquipmentService
  ) {}

  ngOnInit(): void {
    this.loadAll();
  }

  loadAll(): void {
    this.questService.getCharacterQuests().pipe(catchError(() => of([]))).subscribe(q => {
      this.characterQuests = q;
    });
    this.questService.getAvailableQuests().pipe(catchError(() => of([]))).subscribe(q => {
      this.availableQuests = q;
    });
    this.skillService.getLearnedSkills().pipe(catchError(() => of([]))).subscribe(s => {
      this.learnedSkills = s;
    });
    this.skillService.getSkillSlots().pipe(catchError(() => of({}))).subscribe(ss => {
      this.skillSlots = ss;
    });
    this.equipmentService.getEquipment().pipe(catchError(() => of([]))).subscribe(e => {
      this.equipmentSlots = e;
    });
  }

  selectTab(tab: MenuTab): void {
    this.activeTab = tab;
    this.selectedItem = null;
    this.pendingSkillAssign = null;
  }

  close(): void {
    this.closed.emit();
  }

  @HostListener('document:keydown.escape')
  onEsc(): void {
    if (this.isOpen) {
      this.close();
    }
  }

  activeQuests(): CharacterQuest[] {
    return this.characterQuests.filter(cq => cq.status === 'ACTIVE');
  }

  availableCharacterQuests(): CharacterQuest[] {
    return this.characterQuests.filter(cq => cq.status === 'AVAILABLE');
  }

  questProgress(cq: CharacterQuest): number {
    const total = cq.quest.objectives.reduce((sum, o) => sum + o.requiredCount, 0);
    return total > 0 ? Math.min(100, Math.round((cq.progress / total) * 100)) : 0;
  }

  acceptQuest(questId: number): void {
    this.questService.acceptQuest(questId).pipe(catchError(() => of(null))).subscribe(result => {
      if (result) {
        this.questService.getCharacterQuests().pipe(catchError(() => of([]))).subscribe(q => {
          this.characterQuests = q;
        });
      }
    });
  }

  getEquipmentSlot(slotName: string): EquipmentSlot | undefined {
    return this.equipmentSlots.find(s => s.slot === slotName);
  }

  selectEquipmentSlot(slot: EquipmentSlot | undefined): void {
    if (slot) {
      this.selectedItem = this.selectedItem?.slot === slot.slot ? null : slot;
    }
  }

  getStatModifierEntries(slot: EquipmentSlot | null): { key: string; value: number }[] {
    if (!slot?.item?.statModifiers) return [];
    return Object.entries(slot.item.statModifiers).map(([key, value]) => ({ key, value }));
  }

  getSkillInSlot(slot: number): Skill | null {
    return this.skillSlots[slot] ?? null;
  }

  selectSkillForAssign(skill: Skill): void {
    this.pendingSkillAssign = this.pendingSkillAssign?.id === skill.id ? null : skill;
  }

  assignToSlot(slot: number): void {
    if (!this.pendingSkillAssign) return;
    const skillId = this.pendingSkillAssign.id;
    this.skillService.assignSkill(slot, skillId).pipe(catchError(() => of({}))).subscribe(ss => {
      this.skillSlots = ss;
      this.pendingSkillAssign = null;
    });
  }
}
