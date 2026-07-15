import { Component, ElementRef, EventEmitter, HostListener, Input, OnDestroy, OnInit, Output, inject, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';

import {
  VnPlayerState,
  advance as engineAdvance,
  choose as engineChoose,
  enterScene as engineEnterScene,
  initialState,
  pickStartScene,
  withChoices as engineWithChoices,
} from './vn-player-engine';

/**
 * The component reuses the maker's interfaces — they are repeated here so the
 * player is self-contained (no cross-file import of the inline interfaces in
 * vn-maker-remote.component.ts) but the field names match the recon contract
 * exactly (camelCase, confirmed against the vn-builder entities).
 */
export interface VnProject    { id: string; slug: string; displayName: string; description?: string; }
export interface VnCharacter  { id: string; projectId: string; slug: string; displayName: string; color: string; defaultPortraitAssetId?: string; }
export interface VnBackground { id: string; projectId: string; slug: string; name: string; assetId?: string; }
export interface VnScene      { id: string; projectId: string; actId: string; slug: string; name: string; backgroundId?: string; sortOrder: number; entryPoint: boolean; pausesOnEnter?: boolean; }
export type      VnLineKind   = 'SAY' | 'PAUSE' | 'RESUME';
export interface VnLine       { id: string; sceneId: string; lineIndex: number; characterId?: string; text: string; kind?: VnLineKind; }
export interface VnChoice     { id: string; lineId: string; label: string; targetSceneId: string; sortOrder: number; }

/**
 * In-studio web VN preview / player.
 *
 * Drives the pure {@link ./vn-player-engine} with project data fetched lazily
 * per scene (lines on scene entry, a line's choices when its line is reached)
 * and renders a real VN stage: full-bleed background, the speaking
 * character's portrait sprite, a semi-transparent dialogue box with the
 * speaker's coloured name + the line text, a Next affordance (click / button /
 * Enter / Space) and per-line choice buttons that branch to a target scene.
 *
 * The host (VnMakerRemoteComponent) opens this as an overlay for the selected
 * project and listens to (close) to return to the editor.
 */
@Component({
  selector: 'app-vn-player',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="vn-player-overlay" role="dialog" aria-modal="true" [attr.aria-label]="'Preview of ' + project.displayName">
      <button type="button" class="vn-close" (click)="close.emit()" aria-label="Close preview and return to editor">
        &#10005; Back to editor
      </button>

      @if (loadError) {
        <p class="vn-error" role="alert">{{ loadError }}</p>
      }

      <div class="vn-stage"
           [class.clickable]="canAdvanceByClick()"
           (click)="onStageClick()"
           [style.backgroundColor]="stageFallbackColor()">
        <!-- Background image (scene.backgroundId -> background.assetId) -->
        @if (currentBackgroundUrl()) {
          <img class="vn-bg" [src]="currentBackgroundUrl()" [alt]="currentBackgroundAlt()" />
        }

        <!-- Speaking character portrait sprite -->
        @if (currentSpriteUrl()) {
          <img class="vn-sprite" [src]="currentSpriteUrl()" [alt]="currentSpeakerName() + ' portrait'" />
        }

        <!-- Dialogue box -->
        <div class="vn-dialogue" [class.fade]="fading">
          @if (state.done) {
            <p class="vn-the-end">The End</p>
            <button type="button" class="vn-choice" (click)="restart(); $event.stopPropagation()">Play again</button>
          } @else if (state.line) {
            @if (currentSpeakerName()) {
              <p class="vn-speaker" [style.color]="currentSpeakerColor()">{{ currentSpeakerName() }}</p>
            }
            <p class="vn-text">{{ state.line.text }}</p>

            @if (state.choices.length > 0) {
              <div class="vn-choices" role="group" aria-label="Choices">
                @for (c of state.choices; track c.id) {
                  <button type="button" class="vn-choice" (click)="pick(c); $event.stopPropagation()">{{ c.label }}</button>
                }
              </div>
            } @else {
              <button type="button" class="vn-next" (click)="next(); $event.stopPropagation()" aria-label="Next line">
                Next &#9654;
              </button>
            }
          } @else if (!loadError) {
            <p class="vn-text">Loading&hellip;</p>
          }
        </div>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    .vn-player-overlay {
      position: fixed; inset: 0; z-index: 1000;
      background: #1a0a2e; color: #f4eef9;
      display: flex; flex-direction: column;
      font-family: system-ui, sans-serif;
    }
    .vn-close {
      align-self: flex-end; margin: 0.6rem;
      padding: 0.4rem 0.9rem; border-radius: 4px;
      border: 1px solid #b88adb; background: rgba(90, 32, 144, 0.85);
      color: #f4eef9; cursor: pointer; z-index: 3;
    }
    .vn-error {
      background: #f9d5d5; color: #8b1f1f;
      padding: 0.6rem 1rem; border-radius: 4px; margin: 0 0.6rem;
    }
    .vn-stage {
      position: relative; flex: 1; overflow: hidden;
      display: flex; align-items: flex-end; justify-content: center;
    }
    .vn-stage.clickable { cursor: pointer; }
    .vn-bg {
      position: absolute; inset: 0;
      width: 100%; height: 100%;
      object-fit: cover; z-index: 0;
      animation: vn-fade-in 0.4s ease;
    }
    .vn-sprite {
      position: absolute; bottom: 0; left: 50%;
      transform: translateX(-50%);
      max-height: 88%; max-width: 60%;
      object-fit: contain; z-index: 1;
      filter: drop-shadow(0 0 16px rgba(0, 0, 0, 0.5));
      animation: vn-fade-in 0.4s ease;
    }
    .vn-dialogue {
      position: relative; z-index: 2;
      width: min(92%, 880px); margin: 0 auto 6%;
      background: rgba(26, 10, 46, 0.82);
      border: 1px solid #b88adb; border-radius: 10px;
      padding: 1rem 1.4rem 1.2rem;
      backdrop-filter: blur(2px);
      transition: opacity 0.25s ease;
      min-height: 4.5rem;
    }
    .vn-dialogue.fade { opacity: 0; }
    .vn-speaker { margin: 0 0 0.3rem; font-weight: 700; font-size: 1.1rem; }
    .vn-text { margin: 0; font-size: 1.15rem; line-height: 1.5; white-space: pre-wrap; }
    .vn-the-end { margin: 0 0 0.8rem; font-size: 1.6rem; text-align: center; letter-spacing: 0.1em; }
    .vn-next {
      margin-top: 0.8rem; padding: 0.4rem 1rem;
      border-radius: 4px; border: 1px solid #b88adb;
      background: #5a2090; color: #f4eef9; cursor: pointer; font-size: 1rem;
    }
    .vn-choices { display: flex; flex-direction: column; gap: 0.5rem; margin-top: 0.8rem; }
    .vn-choice {
      padding: 0.6rem 1rem; text-align: left;
      border-radius: 6px; border: 1px solid #b88adb;
      background: rgba(90, 32, 144, 0.7); color: #f4eef9;
      cursor: pointer; font-size: 1.05rem;
      transition: background 0.15s ease;
    }
    .vn-choice:hover { background: rgba(184, 138, 219, 0.85); }
    .vn-next:focus-visible, .vn-choice:focus-visible, .vn-close:focus-visible {
      outline: 3px solid #b88adb; outline-offset: 2px;
    }
    @keyframes vn-fade-in { from { opacity: 0; } to { opacity: 1; } }
  `],
})
export class VnPlayerComponent implements OnInit, OnDestroy {
  /** Project to preview. */
  @Input({ required: true }) project!: VnProject;
  /** Emitted when the publisher closes the preview to return to the editor. */
  @Output() close = new EventEmitter<void>();

  private http = inject(HttpClient);
  private readonly hostEl: ElementRef<HTMLElement> = inject(ElementRef);

  /** Standard focusable-element selector for the overlay's focus trap.
   *  Self-contained here (not imported from the host app's
   *  FocusTrapDirective) because this is a separately-built federated
   *  project — see frontend/src/app/directives/focus-trap.directive.ts
   *  for the sibling implementation in the host shell. Keep in sync.
   *  Re-queried live on every Tab press (not cached), since the choice
   *  buttons / Next button swap in and out as the preview advances. */
  private static readonly FOCUSABLE_SELECTOR = [
    'a[href]', 'button:not([disabled])', 'input:not([disabled])',
    'select:not([disabled])', 'textarea:not([disabled])',
    '[tabindex]:not([tabindex="-1"])',
  ].join(',');

  /** Element that held focus before this preview overlay opened —
   *  restored on close so the publisher isn't stranded at the top of
   *  the page. */
  private previouslyFocused: HTMLElement | null = null;

  private focusableElements(): HTMLElement[] {
    return Array.from(
      this.hostEl.nativeElement.querySelectorAll<HTMLElement>(VnPlayerComponent.FOCUSABLE_SELECTOR),
    );
  }

  state: VnPlayerState = initialState();
  loadError = '';
  fading = false;

  /** All scenes for the project (loaded once on open). */
  private scenes: VnScene[] = [];
  /** Characters / backgrounds for name+colour and image resolution. */
  private characters: VnCharacter[] = [];
  private backgrounds: VnBackground[] = [];

  /** Lazy caches keyed by id. */
  private linesByScene: Record<string, VnLine[]> = {};
  private choicesByLine: Record<string, VnChoice[]> = {};
  /** Object-URLs keyed by asset id (revoked on destroy). */
  assetUrls: Record<string, string> = {};
  /** Asset ids we've already kicked a fetch for, so we don't double-GET. */
  private assetRequested = new Set<string>();

  ngOnInit(): void {
    // Keyboard-trap remediation: role="dialog" aria-modal="true" alone
    // only tells assistive tech the rest of the page is inert — it does
    // nothing to keep Tab/Shift+Tab actually cycling within the overlay.
    // Remember what had focus (restored in ngOnDestroy) and move focus
    // into the dialog. The .vn-close button is always present on first
    // render regardless of the async project data still loading.
    this.previouslyFocused = document.activeElement as HTMLElement | null;
    const host = this.hostEl.nativeElement;
    if (!host.hasAttribute('tabindex')) host.setAttribute('tabindex', '-1');
    const focusable = this.focusableElements();
    (focusable[0] ?? host).focus();

    const pid = this.project.id;
    // Fan out the project-level GETs, then start the engine once scenes land.
    this.http.get<VnCharacter[]>(`/api/vn/projects/${pid}/characters`).subscribe({
      next: r => this.characters = r,
      error: e => this.loadError = this.fmt(e, 'load characters'),
    });
    this.http.get<VnBackground[]>(`/api/vn/projects/${pid}/backgrounds`).subscribe({
      next: r => { this.backgrounds = r; this.prefetchBackground(); },
      error: e => this.loadError = this.fmt(e, 'load backgrounds'),
    });
    this.http.get<VnScene[]>(`/api/vn/projects/${pid}/scenes`).subscribe({
      next: r => { this.scenes = r; this.start(); },
      error: e => this.loadError = this.fmt(e, 'load scenes'),
    });
  }

  ngOnDestroy(): void {
    for (const url of Object.values(this.assetUrls)) URL.revokeObjectURL(url);
    this.assetUrls = {};
    // Restore focus to whatever had it before the preview opened, so
    // closing the overlay doesn't strand focus at the top of the page.
    const target = this.previouslyFocused;
    if (target && typeof target.focus === 'function' && document.contains(target)) {
      target.focus();
    }
  }

  // ---- engine driving -----------------------------------------------------

  /** Begin at the entryPoint scene (else lowest sortOrder); fetch its lines. */
  start(): void {
    const scene = pickStartScene(this.scenes);
    if (!scene) { this.state = { sceneId: null, lineId: null, line: null, choices: [], done: true }; return; }
    this.loadSceneLines(scene.id, lines => {
      this.state = engineEnterScene(scene, lines);
      this.afterLineChange();
    });
  }

  restart(): void { this.loadError = ''; this.start(); }

  /** Next affordance: linear advance to the next line (or graceful end). */
  next(): void {
    if (this.state.done || !this.state.sceneId || this.state.choices.length > 0) return;
    const sceneLines = this.linesByScene[this.state.sceneId] ?? [];
    this.transition(() => {
      this.state = engineAdvance(this.state, sceneLines);
      this.afterLineChange();
    });
  }

  /** Pick a choice: jump to its target scene's first line. */
  pick(choice: VnChoice): void {
    const target = this.scenes.find(s => s.id === choice.targetSceneId) ?? null;
    if (!target) { this.state = engineChoose(choice, null, []); return; }
    this.loadSceneLines(target.id, lines => {
      this.transition(() => {
        this.state = engineChoose(choice, target, lines);
        this.afterLineChange();
      });
    });
  }

  /** Stage click = Next, but only when a plain Next is the valid action. */
  onStageClick(): void { if (this.canAdvanceByClick()) this.next(); }

  canAdvanceByClick(): boolean {
    return !this.state.done && !!this.state.line && this.state.choices.length === 0;
  }

  @HostListener('document:keydown', ['$event'])
  onKeydown(ev: KeyboardEvent): void {
    if (ev.key === 'Escape') { this.close.emit(); return; }
    if ((ev.key === 'Enter' || ev.key === ' ') && this.canAdvanceByClick()) {
      ev.preventDefault();
      this.next();
      return;
    }
    if (ev.key === 'Tab') { this.trapTab(ev); }
  }

  /** Tab from the last focusable element wraps to the first; Shift+Tab
   *  from the first wraps to the last — keeps keyboard users cycling
   *  within the overlay instead of escaping into the (visually inert)
   *  maker UI behind it. Bound at the document level since this
   *  component's whole template IS the modal (a fixed full-viewport
   *  overlay), and the component instance only exists while it's open. */
  private trapTab(ev: KeyboardEvent): void {
    const host = this.hostEl.nativeElement;
    const focusable = this.focusableElements();
    if (focusable.length === 0) {
      ev.preventDefault();
      host.focus();
      return;
    }
    const first = focusable[0];
    const last = focusable[focusable.length - 1];
    const active = document.activeElement;
    if (ev.shiftKey) {
      // Also wrap when focus is on the overlay host itself (tabindex=-1
      // fallback) or has escaped outside it — not just from the first
      // focusable — so Shift+Tab never leaks into the inert maker UI.
      if (active === host || active === first || !host.contains(active)) {
        ev.preventDefault(); last.focus();
      }
    } else {
      if (active === last) { ev.preventDefault(); first.focus(); }
    }
  }

  /**
   * After landing on a new line: load that line's choices (lazy) and surface
   * them on state, prefetch the scene's background, and prefetch the speaker
   * sprite.
   */
  private afterLineChange(): void {
    this.prefetchBackground();
    this.prefetchSprite();
    const line = this.state.line;
    if (!line) return;
    const cached = this.choicesByLine[line.id];
    if (cached) { this.state = engineWithChoices(this.state, cached); return; }
    this.http.get<VnChoice[]>(`/api/vn/projects/${this.project.id}/lines/${line.id}/choices`).subscribe({
      next: cc => {
        this.choicesByLine[line.id] = cc;
        // Only attach if we're still on the same line (avoids a late
        // response clobbering a faster Next).
        if (this.state.line?.id === line.id) this.state = engineWithChoices(this.state, cc);
      },
      error: e => this.loadError = this.fmt(e, 'load choices'),
    });
  }

  /** Fetch + cache a scene's lines, then run cb with them. */
  private loadSceneLines(sceneId: string, cb: (lines: VnLine[]) => void): void {
    const cached = this.linesByScene[sceneId];
    if (cached) { cb(cached); return; }
    this.http.get<VnLine[]>(`/api/vn/projects/${this.project.id}/scenes/${sceneId}/lines`).subscribe({
      next: ll => { this.linesByScene[sceneId] = ll; cb(ll); },
      error: e => this.loadError = this.fmt(e, 'load lines'),
    });
  }

  /** Subtle fade between lines/scenes around a state swap. */
  private transition(swap: () => void): void {
    this.fading = true;
    setTimeout(() => { swap(); this.fading = false; }, 120);
  }

  // ---- image + speaker resolution -----------------------------------------

  private currentScene(): VnScene | null {
    return this.state.sceneId ? this.scenes.find(s => s.id === this.state.sceneId) ?? null : null;
  }

  private currentBackground(): VnBackground | null {
    const scene = this.currentScene();
    return scene?.backgroundId ? this.backgrounds.find(b => b.id === scene.backgroundId) ?? null : null;
  }

  currentBackgroundUrl(): string | null {
    const aid = this.currentBackground()?.assetId;
    return aid ? this.assetUrls[aid] ?? null : null;
  }

  currentBackgroundAlt(): string {
    return this.currentBackground()?.name ?? 'Scene background';
  }

  stageFallbackColor(): string {
    // Pleasant fallback when a scene has no background asset.
    return this.currentBackgroundUrl() ? 'transparent' : '#2c1140';
  }

  private currentCharacter(): VnCharacter | null {
    const cid = this.state.line?.characterId;
    return cid ? this.characters.find(c => c.id === cid) ?? null : null;
  }

  currentSpeakerName(): string {
    return this.currentCharacter()?.displayName ?? '';
  }

  currentSpeakerColor(): string {
    return this.currentCharacter()?.color ?? '#f4eef9';
  }

  currentSpriteUrl(): string | null {
    const aid = this.currentCharacter()?.defaultPortraitAssetId;
    return aid ? this.assetUrls[aid] ?? null : null;
  }

  /** Lazily GET an image asset's bytes as an object URL keyed by asset id. */
  private loadAsset(aid: string): void {
    if (this.assetRequested.has(aid)) return;
    this.assetRequested.add(aid);
    this.http.get(`/api/vn/projects/${this.project.id}/assets/${aid}`, { responseType: 'blob' }).subscribe({
      next: (blob: Blob) => { this.assetUrls = { ...this.assetUrls, [aid]: URL.createObjectURL(blob) }; },
      error: e => this.loadError = this.fmt(e, 'load asset image'),
    });
  }

  private prefetchBackground(): void {
    const aid = this.currentBackground()?.assetId;
    if (aid) this.loadAsset(aid);
  }

  private prefetchSprite(): void {
    const aid = this.currentCharacter()?.defaultPortraitAssetId;
    if (aid) this.loadAsset(aid);
  }

  private fmt(err: unknown, action: string): string {
    if (err instanceof Error) return `${action} failed: ${err.message}`;
    const e = err as { status?: number; error?: { message?: string; detail?: string }; message?: string };
    if (typeof e?.status === 'number') {
      const detail = (e.error && (e.error.message || e.error.detail)) || e.message;
      return `${action} failed (${e.status}): ${detail}`;
    }
    return `${action} failed: ${String(err)}`;
  }
}
