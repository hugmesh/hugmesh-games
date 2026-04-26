import { Component, HostListener, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ControlService } from '../services/control.service';
import { GameAction } from '../models/game-state.model';
import { environment } from '../../environments/environment';

interface ControlButton {
  action: GameAction;
  label: string;
  key: string;
  icon: string;
  altKey?: string;
  pressed: boolean;
}

interface SkillSlot {
  slot: number;
  name: string | null;
  icon: string;
  manaCost: number;
}

interface ItemSlot {
  name: string | null;
  icon: string;
  quantity: number;
}

@Component({
  selector: 'app-game-controls',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-controls.component.html',
  styleUrl: './game-controls.component.scss'
})
export class GameControlsComponent implements OnInit, OnDestroy {
  private readonly http = inject(HttpClient);

  readonly movementButtons: ControlButton[] = [
    { action: 'MOVE_FORWARD',  label: 'Forward',  key: 'w', altKey: 'ArrowUp',    icon: '▲', pressed: false },
    { action: 'MOVE_LEFT',     label: 'Left',     key: 'a', altKey: 'ArrowLeft',  icon: '◄', pressed: false },
    { action: 'MOVE_BACKWARD', label: 'Back',     key: 's', altKey: 'ArrowDown',  icon: '▼', pressed: false },
    { action: 'MOVE_RIGHT',    label: 'Right',    key: 'd', altKey: 'ArrowRight', icon: '►', pressed: false },
    { action: 'JUMP',          label: 'Jump',     key: ' ', icon: '⬆', pressed: false },
  ];

  /** Spell hotbar — five slots, fed live from /api/skills/character/1/slots. */
  readonly spellSlots: SkillSlot[] = [
    { slot: 1, name: 'Fireball',     icon: '🔥', manaCost: 10 },
    { slot: 2, name: 'Ice Shard',    icon: '❄',  manaCost: 8  },
    { slot: 3, name: 'Thunder Bolt', icon: '⚡', manaCost: 12 },
    { slot: 4, name: 'Stone Spike',  icon: '🪨', manaCost: 11 },
    { slot: 5, name: 'Holy Light',   icon: '✨', manaCost: 14 },
  ];

  /** Item quick-slot — first inventory item, used on E. */
  itemSlot: ItemSlot = { name: null, icon: '🎒', quantity: 0 };

  /** Whether the on-screen movement D-pad is visible. Toggled by the chevron
   *  button at the corner. Persists for the session in sessionStorage so a
   *  reload preserves the player's preference. */
  dpadVisible = sessionStorage.getItem('tw_dpad_hidden') !== '1';

  toggleDpad(): void {
    this.dpadVisible = !this.dpadVisible;
    sessionStorage.setItem('tw_dpad_hidden', this.dpadVisible ? '0' : '1');
  }

  readonly interactBtn: ControlButton =
    { action: 'INTERACT', label: 'Use Item', key: 'e', icon: '🤝', pressed: false };

  private readonly _keyMap = new Map<string, ControlButton>();
  private readonly _spellAction: Record<number, GameAction> = {
    1: 'CAST_INSTANT_1', 2: 'CAST_INSTANT_2', 3: 'CAST_INSTANT_3',
    4: 'CAST_INSTANT_4', 5: 'CAST_INSTANT_5',
  };

  constructor(private controls: ControlService) {
    [...this.movementButtons, this.interactBtn].forEach(btn => {
      this._keyMap.set(btn.key.toLowerCase(), btn);
      if (btn.altKey) this._keyMap.set(btn.altKey, btn);
    });
  }

  ngOnInit(): void {
    this._refreshSpells();
    this._refreshItem();
  }

  private _refreshSpells(): void {
    this.http.get<{ slot: number; skill: { name: string; manaCost: number } | null }[]>(
      `${environment.apiBase}/api/skills/character/1/slots`
    ).subscribe({
      next: rows => rows.forEach(row => {
        const slot = this.spellSlots.find(s => s.slot === row.slot);
        if (slot && row.skill) {
          slot.name = row.skill.name;
          slot.manaCost = row.skill.manaCost;
        }
      }),
      error: () => { /* keep defaults */ },
    });
  }

  private _refreshItem(): void {
    this.http.get<{ name: string; quantity: number; itemType: string }[]>(
      `${environment.apiBase}/api/items/character/1/inventory`
    ).subscribe({
      next: items => {
        if (items.length === 0) { this.itemSlot = { name: null, icon: '🎒', quantity: 0 }; return; }
        const first = items[0];
        this.itemSlot = {
          name: first.name,
          icon: this._iconFor(first.name, first.itemType),
          quantity: first.quantity,
        };
      },
      error: () => { /* leave empty */ },
    });
  }

  private _iconFor(name: string, type: string): string {
    if (/potion|ether/i.test(name)) return '🧪';
    if (/sword|blade/i.test(name)) return '⚔';
    if (/wand|staff/i.test(name))  return '🪄';
    if (/bow/i.test(name))          return '🏹';
    if (/feather/i.test(name))      return '🪶';
    if (/antidote/i.test(name))     return '💊';
    if (/robe/i.test(name))         return '🥋';
    if (/boots/i.test(name))        return '🥾';
    if (/helm/i.test(name))         return '⛑';
    return type === 'consumable' ? '🧪' : '🎒';
  }

  @HostListener('window:keydown', ['$event'])
  onKeyDown(e: KeyboardEvent): void {
    if (e.repeat) return;
    if (e.key >= '1' && e.key <= '5') {
      e.preventDefault();
      const slot = parseInt(e.key, 10);
      this._fireSpellOverlay(slot);
      this.controls.sendControl(this._spellAction[slot], true);
      setTimeout(() => this.controls.sendControl(this._spellAction[slot], false), 50);
      return;
    }
    if (e.key === ' ') e.preventDefault();

    const btn = this._keyMap.get(e.key === ' ' ? ' ' : e.key.toLowerCase()) ??
                this._keyMap.get(e.key);
    if (btn && !btn.pressed) {
      btn.pressed = true;
      this.controls.sendControl(btn.action, true);
      if (btn.action === 'INTERACT') setTimeout(() => this._refreshItem(), 250);
    }
  }

  @HostListener('window:keyup', ['$event'])
  onKeyUp(e: KeyboardEvent): void {
    if (e.key >= '1' && e.key <= '5') return;
    const btn = this._keyMap.get(e.key === ' ' ? ' ' : e.key.toLowerCase()) ??
                this._keyMap.get(e.key);
    if (btn && btn.pressed) {
      btn.pressed = false;
      this.controls.sendControl(btn.action, false);
    }
  }

  onButtonDown(btn: ControlButton): void {
    if (!btn.pressed) {
      btn.pressed = true;
      this.controls.sendControl(btn.action, true);
    }
  }

  onButtonUp(btn: ControlButton): void {
    if (btn.pressed) {
      btn.pressed = false;
      this.controls.sendControl(btn.action, false);
      if (btn.action === 'INTERACT') setTimeout(() => this._refreshItem(), 250);
    }
  }

  onSpellClick(slot: SkillSlot): void {
    this._fireSpellOverlay(slot.slot);
    this.controls.sendControl(this._spellAction[slot.slot], true);
    setTimeout(() => this.controls.sendControl(this._spellAction[slot.slot], false), 50);
  }

  /** Always-on CSS spell overlay so SOMETHING is visible regardless of
   *  iframe/WASM state. If this doesn't appear on keypress, the key isn't
   *  reaching Angular at all (iframe has focus — click outside to retry). */
  private _fireSpellOverlay(slot: number): void {
    const colors = ['#ff6b1a', '#7cc4ff', '#ffe14a', '#9c7050', '#fff5cc'];
    const el = document.createElement('div');
    el.className = 'tw-spell-overlay';
    el.style.setProperty('--spell-color', colors[slot - 1] ?? '#7c4dff');
    el.style.setProperty('--spell-size', `${48 + slot * 10}px`);
    document.body.appendChild(el);
    el.addEventListener('animationend', () => el.remove(), { once: true });
  }

  onItemClick(): void {
    if (!this.itemSlot.name) return;
    this.controls.sendControl('INTERACT', true);
    setTimeout(() => {
      this.controls.sendControl('INTERACT', false);
      this._refreshItem();
    }, 100);
  }

  ngOnDestroy(): void {
    [...this.movementButtons, this.interactBtn]
      .filter(b => b.pressed)
      .forEach(b => { b.pressed = false; this.controls.sendControl(b.action, false); });
  }
}
