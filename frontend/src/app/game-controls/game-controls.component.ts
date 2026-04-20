import { Component, HostListener, OnDestroy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ControlService } from '../services/control.service';
import { GameAction } from '../models/game-state.model';

interface ControlButton {
  action: GameAction;
  label: string;
  key: string;
  icon: string;
  altKey?: string;
  pressed: boolean;
}

@Component({
  selector: 'app-game-controls',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './game-controls.component.html',
  styleUrl: './game-controls.component.scss'
})
export class GameControlsComponent implements OnDestroy {
  readonly movementButtons: ControlButton[] = [
    { action: 'MOVE_FORWARD',  label: 'Forward',  key: 'w', altKey: 'ArrowUp',    icon: '▲', pressed: false },
    { action: 'MOVE_LEFT',     label: 'Left',     key: 'a', altKey: 'ArrowLeft',  icon: '◄', pressed: false },
    { action: 'MOVE_BACKWARD', label: 'Back',     key: 's', altKey: 'ArrowDown',  icon: '▼', pressed: false },
    { action: 'MOVE_RIGHT',    label: 'Right',    key: 'd', altKey: 'ArrowRight', icon: '►', pressed: false },
    { action: 'JUMP',          label: 'Jump',     key: ' ', icon: '⬆', pressed: false },
  ];

  readonly actionButtons: ControlButton[] = [
    { action: 'INTERACT',    label: 'Interact', key: 'e', icon: '🤝', pressed: false },
    { action: 'CAST_SPELL_1', label: 'Spell 1', key: '1', icon: '🔥', pressed: false },
    { action: 'CAST_SPELL_2', label: 'Spell 2', key: '2', icon: '❄',  pressed: false },
    { action: 'CAST_SPELL_3', label: 'Spell 3', key: '3', icon: '⚡', pressed: false },
  ];

  private readonly _keyMap = new Map<string, ControlButton>();

  constructor(private controls: ControlService) {
    [...this.movementButtons, ...this.actionButtons].forEach(btn => {
      this._keyMap.set(btn.key.toLowerCase(), btn);
      if (btn.altKey) this._keyMap.set(btn.altKey, btn);
    });
  }

  @HostListener('window:keydown', ['$event'])
  onKeyDown(e: KeyboardEvent): void {
    if (e.repeat) return;
    const btn = this._keyMap.get(e.key === ' ' ? ' ' : e.key.toLowerCase()) ??
                this._keyMap.get(e.key);
    if (btn && !btn.pressed) {
      btn.pressed = true;
      this.controls.sendControl(btn.action, true);
    }
  }

  @HostListener('window:keyup', ['$event'])
  onKeyUp(e: KeyboardEvent): void {
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
    }
  }

  ngOnDestroy(): void {
    [...this.movementButtons, ...this.actionButtons]
      .filter(b => b.pressed)
      .forEach(b => { b.pressed = false; this.controls.sendControl(b.action, false); });
  }
}
