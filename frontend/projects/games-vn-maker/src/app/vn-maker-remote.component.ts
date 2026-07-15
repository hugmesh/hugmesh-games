import { Component, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, of, timer } from 'rxjs';
import { catchError, switchMap, takeUntil } from 'rxjs/operators';
import { VnPlayerComponent } from './vn-player.component';

interface VnProject    { id: string; slug: string; displayName: string; description?: string; }
interface VnAct        { id: string; projectId: string; slug: string; name: string; sortOrder: number; }
interface VnCharacter  { id: string; projectId: string; slug: string; displayName: string; color: string; defaultPortraitAssetId?: string; }
interface VnBackground { id: string; projectId: string; slug: string; name: string; assetId?: string; }
interface VnScene      { id: string; projectId: string; actId: string; slug: string; name: string; backgroundId?: string; sortOrder: number; entryPoint: boolean; pausesOnEnter?: boolean; }
type    VnLineKind     = 'SAY' | 'PAUSE' | 'RESUME';
interface VnLine       { id: string; sceneId: string; lineIndex: number; characterId?: string; text: string; kind?: VnLineKind; }
interface VnChoice     { id: string; lineId: string; label: string; targetSceneId: string; sortOrder: number; }
interface VnBuildJob   { id: string; status: string; progressPct: number; logTail: string; targetSlug: string; errorMessage?: string; }
interface VnAsset      { id: string; projectId: string; slug: string; kind: string; mimeType: string; byteSize: number; storagePath?: string; uploadedBy?: string; uploadedAt?: string; }

const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
const ASSET_KINDS = ['background', 'portrait'] as const;
const ASSET_THEMES = ['dawn', 'dusk', 'interior', 'forest', 'void'] as const;
const IMAGE_MIMES = ['image/png', 'image/jpeg', 'image/webp', 'image/svg+xml'];

/**
 * Visual-novel editor remote, shaped after godot-2d-visual-novel.
 *
 * Three-pane layout: Projects | Acts+Scenes | Lines+Choices+Build.
 * Inline form-validation messages on every input so a publisher
 * never has to guess what a 400 means; the BFF / vn-builder shared
 * slug regex is mirrored client-side for fast-feedback rejects.
 */
@Component({
  selector: 'app-vn-maker-remote',
  standalone: true,
  imports: [CommonModule, FormsModule, VnPlayerComponent],
  template: `
    <div class="vn-maker">
      <header>
        <h1>&#9670; Visual Novel Maker</h1>
        <div class="header-actions">
          @if (selectedProject) {
            <button class="btn primary-btn" type="button" (click)="openPreview()"
                    title="Play the branching visual novel live in the browser">
              &#9654; Preview
            </button>
          }
          <button class="btn" (click)="refreshProjects()">&#8635; Refresh</button>
        </div>
      </header>

      @if (errorBanner) { <p class="error-banner" role="alert" aria-live="assertive">{{ errorBanner }}</p> }
      @if (successBanner) { <p class="success-banner" role="status">{{ successBanner }}</p> }

      <div class="three-pane">
        <!-- Projects -->
        <section class="pane">
          <h2>Projects</h2>
          @if (projects$ | async; as projects) {
            <ul class="row-list">
              @for (p of projects; track p.id) {
                <li>
                  <button class="link-btn" type="button"
                          [class.active]="selectedProject?.id === p.id"
                          (click)="selectProject(p)">{{ p.displayName }} <small>({{ p.slug }})</small></button>
                </li>
              }
            </ul>
          }
          <fieldset>
            <legend>New project</legend>
            <label>Slug
              <input name="newProjSlug" [(ngModel)]="newProj.slug" placeholder="my-vn" />
            </label>
            @if (newProj.slug && !slugOk(newProj.slug)) {
              <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
            }
            <label>Display name
              <input name="newProjName" [(ngModel)]="newProj.displayName" />
            </label>
            @if ((submitted.project || slugOk(newProj.slug)) && !newProj.displayName?.trim()) {
              <p class="field-error" role="alert">display name is required.</p>
            }
            <label>Description
              <textarea name="newProjDesc" [(ngModel)]="newProj.description" rows="2"></textarea>
            </label>
            <button type="button" class="primary-btn" (click)="createProject()"
                    [disabled]="!slugOk(newProj.slug) || !newProj.displayName?.trim()">Create</button>
          </fieldset>
          @if (selectedProject) {
            <h3>Cast</h3>
            <ul class="row-list">
              @for (c of characters; track c.id) {
                <li><span [style.color]="c.color">{{ c.displayName }}</span> <small>({{ c.slug }})</small></li>
              }
            </ul>
            <fieldset>
              <legend>New character</legend>
              <label>Slug<input name="newCharSlug" [(ngModel)]="newChar.slug" placeholder="dani" /></label>
              @if (newChar.slug && !slugOk(newChar.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Display name<input name="newCharName" [(ngModel)]="newChar.displayName" /></label>
              @if (submitted.character && !newChar.displayName?.trim()) {
                <p class="field-error" role="alert">character display name is required.</p>
              }
              <label>Colour<input name="newCharColor" [(ngModel)]="newChar.color" placeholder="#aabbcc" /></label>
              @if (newChar.color && !colorOk(newChar.color)) {
                <p class="field-error" role="alert">colour must look like #rgb / #rrggbb.</p>
              }
              <label>Portrait
                <select name="newCharPortrait" [(ngModel)]="newChar.defaultPortraitAssetId">
                  <option value="">— none —</option>
                  @for (a of assets; track a.id) { <option [value]="a.id">{{ a.slug }} ({{ a.kind }})</option> }
                </select>
              </label>
              <button type="button" class="primary-btn" (click)="createCharacter()"
                      [disabled]="!slugOk(newChar.slug) || !newChar.displayName?.trim() || (!!newChar.color && !colorOk(newChar.color))">Add</button>
            </fieldset>

            <h3>Backgrounds</h3>
            <ul class="row-list">
              @for (b of backgrounds; track b.id) {
                <li>{{ b.name }} <small>({{ b.slug }}{{ b.assetId ? ', art' : '' }})</small></li>
              }
            </ul>
            <fieldset>
              <legend>New background</legend>
              <label>Slug<input name="newBgSlug" [(ngModel)]="newBackground.slug" placeholder="dawn-street" /></label>
              @if (newBackground.slug && !slugOk(newBackground.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Name<input name="newBgName" [(ngModel)]="newBackground.name" /></label>
              @if (submitted.background && !newBackground.name?.trim()) {
                <p class="field-error" role="alert">background name is required.</p>
              }
              <label>Art asset
                <select name="newBgAsset" [(ngModel)]="newBackground.assetId">
                  <option value="">— none —</option>
                  @for (a of assets; track a.id) { <option [value]="a.id">{{ a.slug }} ({{ a.kind }})</option> }
                </select>
              </label>
              <button type="button" class="primary-btn" (click)="createBackground()"
                      [disabled]="!slugOk(newBackground.slug) || !newBackground.name?.trim()">Add background</button>
            </fieldset>
          }
        </section>

        <!-- Acts + Scenes -->
        <section class="pane">
          <h2>{{ selectedProject?.displayName || 'Select a project' }}</h2>
          @if (selectedProject) {
            <h3>Acts (each becomes a chapter)</h3>
            <ul class="row-list">
              @for (a of acts; track a.id) {
                <li>
                  <button class="link-btn" type="button"
                          [class.active]="selectedAct?.id === a.id"
                          (click)="selectAct(a)">{{ a.name }} <small>({{ a.slug }})</small></button>
                </li>
              }
            </ul>
            <fieldset>
              <legend>New act</legend>
              <label>Slug<input name="newActSlug" [(ngModel)]="newAct.slug" placeholder="prologue" /></label>
              @if (newAct.slug && !slugOk(newAct.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Name<input name="newActName" [(ngModel)]="newAct.name" /></label>
              @if (submitted.act && !newAct.name?.trim()) {
                <p class="field-error" role="alert">act name is required.</p>
              }
              <button type="button" class="primary-btn" (click)="createAct()"
                      [disabled]="!slugOk(newAct.slug) || !newAct.name?.trim()">Add act</button>
            </fieldset>

            @if (selectedAct) {
              <h3>Scenes in {{ selectedAct.name }}</h3>
              <ul class="row-list">
                @for (s of scenesInSelectedAct(); track s.id) {
                  <li>
                    <button class="link-btn" type="button"
                            [class.active]="selectedScene?.id === s.id"
                            (click)="selectScene(s)">
                      {{ s.name }} <small>({{ s.slug }}{{ s.entryPoint ? ', entry' : '' }})</small>
                    </button>
                  </li>
                }
              </ul>
              <fieldset>
                <legend>New scene</legend>
                <label>Slug<input name="newSceneSlug" [(ngModel)]="newScene.slug" /></label>
                @if (newScene.slug && !slugOk(newScene.slug)) {
                  <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
                }
                <label>Name<input name="newSceneName" [(ngModel)]="newScene.name" /></label>
                @if (submitted.scene && !newScene.name?.trim()) {
                  <p class="field-error" role="alert">scene name is required.</p>
                }
                <label>Background
                  <select name="newSceneBg" [(ngModel)]="newScene.backgroundId">
                    <option value="">— none —</option>
                    @for (b of backgrounds; track b.id) { <option [value]="b.id">{{ b.name }}</option> }
                  </select>
                </label>
                <label><input type="checkbox" name="newSceneEntry" [(ngModel)]="newScene.entryPoint" /> Entry point of this act</label>
                <label><input type="checkbox" name="newScenePause" [(ngModel)]="newScene.pausesOnEnter" /> Pause session when this scene becomes active</label>
                <button type="button" class="primary-btn" (click)="createScene()"
                        [disabled]="!slugOk(newScene.slug) || !newScene.name?.trim()">Add scene</button>
              </fieldset>
            }
          }
        </section>

        <!-- Lines + Choices + Build -->
        <section class="pane">
          <h2>{{ selectedScene?.name || 'Select a scene' }}</h2>
          @if (selectedScene) {
            <h3>Dialogue lines</h3>
            <ul class="row-list">
              @for (l of lines; track l.id) {
                <li>
                  #{{ l.lineIndex }}
                  @if (l.kind && l.kind !== 'SAY') {
                    <span class="line-kind-tag" [attr.data-kind]="l.kind">{{ l.kind }}</span>
                  }
                  <strong>{{ characterName(l.characterId) }}</strong>: {{ l.text }}
                </li>
              }
            </ul>
            <fieldset>
              <legend>New line</legend>
              <label>Kind
                <select name="newLineKind" [(ngModel)]="newLine.kind">
                  <option value="SAY">SAY — speak</option>
                  <option value="PAUSE">PAUSE — fire SESSION_PAUSED when reached</option>
                  <option value="RESUME">RESUME — clear the session pause</option>
                </select>
              </label>
              <label>Speaker
                <select name="newLineChar" [(ngModel)]="newLine.characterId">
                  <option value="">— narrator —</option>
                  @for (c of characters; track c.id) { <option [value]="c.id">{{ c.displayName }}</option> }
                </select>
              </label>
              <label>Text<textarea name="newLineText" [(ngModel)]="newLine.text" rows="2"></textarea></label>
              @if (submitted.line && !newLine.text?.trim()) {
                <p class="field-error" role="alert">line text is required.</p>
              }
              <button type="button" class="primary-btn" (click)="createLine()"
                      [disabled]="!newLine.text?.trim()">Add line</button>
            </fieldset>

            @if (lines.length > 0) {
              <h3>Branches on last line</h3>
              <fieldset>
                <legend>New choice</legend>
                <label>Label<input name="newChoiceLabel" [(ngModel)]="newChoice.label" /></label>
                @if (submitted.choice && !newChoice.label?.trim()) {
                  <p class="field-error" role="alert">choice label is required.</p>
                }
                <label>Target scene
                  <select name="newChoiceTarget" [(ngModel)]="newChoice.targetSceneId">
                    <option value="">— pick —</option>
                    @for (s of scenesInSelectedAct(); track s.id) { <option [value]="s.id">{{ s.name }}</option> }
                  </select>
                </label>
                @if ((submitted.choice || !!newChoice.label?.trim()) && !newChoice.targetSceneId) {
                  <p class="field-error" role="alert">target scene is required.</p>
                }
                <button type="button" class="primary-btn" (click)="createChoice()"
                        [disabled]="!newChoice.label?.trim() || !newChoice.targetSceneId">Add choice</button>
              </fieldset>
            }
          }

          @if (selectedProject) {
            <h3>Build &amp; publish</h3>
            <fieldset>
              <legend>Compile to a Snapdragon-bridge bundle</legend>
              <label>Target slug<input name="vnTarget" [(ngModel)]="buildTargetSlug" /></label>
              @if (buildTargetSlug && !slugOk(buildTargetSlug)) {
                <p class="field-error" role="alert">target slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <button type="button" class="primary-btn" (click)="enqueueBuild()"
                      [disabled]="buildInFlight || (!!buildTargetSlug && !slugOk(buildTargetSlug))">
                @if (buildInFlight) { Building &hellip; } @else { Build &amp; upload }
              </button>
              <button type="button" class="primary-btn"
                      (click)="downloadGodotZip()"
                      [disabled]="!selectedProject"
                      title="Download a Godot project zip you can open in Godot 4 directly">
                &#8595; Download Godot source
              </button>
              @if (currentBuild) {
                <div class="build-progress" role="status">
                  <strong>{{ currentBuild.status }}</strong> — {{ currentBuild.progressPct }}%
                  <progress [value]="currentBuild.progressPct" max="100"></progress>
                  <pre>{{ currentBuild.logTail }}</pre>
                  @if (currentBuild.errorMessage) { <p class="field-error" role="alert">{{ currentBuild.errorMessage }}</p> }
                </div>
              }
            </fieldset>
          }
        </section>

        <!-- Art assets -->
        <section class="pane">
          <h2>Art assets</h2>
          @if (selectedProject) {
            <fieldset>
              <legend>Upload asset</legend>
              <label>Slug
                <input name="newAssetSlug" [(ngModel)]="newAsset.slug" placeholder="dawn-street" />
              </label>
              @if (newAsset.slug && !slugOk(newAsset.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Kind
                <select name="newAssetKind" [(ngModel)]="newAsset.kind">
                  <option value="">— pick —</option>
                  @for (k of assetKinds; track k) { <option [value]="k">{{ k }}</option> }
                </select>
              </label>
              @if (submitted.asset && !newAsset.kind?.trim()) {
                <p class="field-error" role="alert">kind is required.</p>
              }
              <label>File
                <input type="file" name="newAssetFile"
                       accept="image/png,image/jpeg,image/webp,image/svg+xml"
                       (change)="assetFile = ($any($event.target).files?.[0]) ?? null" />
              </label>
              @if (submitted.asset && !assetFile) {
                <p class="field-error" role="alert">a file is required.</p>
              }
              <p class="hint">images must be png / jpeg / webp / svg, max 4 MB.</p>
              <button type="button" class="primary-btn" (click)="uploadAsset()"
                      [disabled]="!slugOk(newAsset.slug) || !newAsset.kind?.trim() || !assetFile">Upload asset</button>
            </fieldset>

            <fieldset>
              <legend>Generate art</legend>
              <p class="hint">Make house-style placeholder SVG art without leaving the studio.</p>
              <label>Slug
                <input name="genAssetSlug" [(ngModel)]="genAsset.slug" placeholder="dusk-plaza" />
              </label>
              @if (genAsset.slug && !slugOk(genAsset.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Kind
                <select name="genAssetKind" [(ngModel)]="genAsset.kind">
                  @for (k of assetKinds; track k) { <option [value]="k">{{ k }}</option> }
                </select>
              </label>
              <label>Theme / seed (optional)
                <input name="genAssetTheme" [(ngModel)]="genAsset.theme"
                       list="vnGenThemes"
                       placeholder="dawn / dusk / interior / forest / void, or #rrggbb" />
              </label>
              <datalist id="vnGenThemes">
                @for (t of assetThemes; track t) { <option [value]="t"></option> }
              </datalist>
              <p class="hint">Backgrounds theme by name; portraits tint from a #rrggbb colour (or the slug).</p>
              <button type="button" class="primary-btn" (click)="generateAsset()"
                      [disabled]="!slugOk(genAsset.slug) || !genAsset.kind || generating">
                @if (generating) { Generating &hellip; } @else { Generate art }
              </button>
            </fieldset>

            <h3>Gallery <small>(this project's assets)</small></h3>
            <ul class="row-list asset-grid">
              @for (a of assets; track a.id) {
                <li class="asset-card">
                  @if (isImageAsset(a) && assetUrls[a.id]) {
                    <img class="asset-thumb" [src]="assetUrls[a.id]" [alt]="a.slug" />
                  }
                  <strong>{{ a.slug }}</strong>
                  <small>{{ a.kind }} &middot; {{ a.mimeType }}</small>
                  <button type="button" class="link-btn" (click)="copySlug(a.slug)">copy slug</button>
                </li>
              }
            </ul>
          }
        </section>
      </div>

      @if (previewing && selectedProject) {
        <app-vn-player [project]="selectedProject" (close)="closePreview()"></app-vn-player>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    .vn-maker { padding: 1rem; font-family: system-ui, sans-serif; }
    header { display: flex; justify-content: space-between; align-items: center; }
    .header-actions { display: flex; gap: 0.5rem; }
    h1 { margin: 0; font-size: 1.4rem; }
    .three-pane { display: grid; grid-template-columns: 1fr 1.4fr 1.4fr; gap: 1rem; margin-top: 1rem; }
    .pane { background: #f4eef9; border: 1px solid #b88adb; border-radius: 6px; padding: 0.8rem; }
    .pane h2 { margin: 0 0 0.4rem; font-size: 1.05rem; }
    .pane h3 { margin: 0.6rem 0 0.3rem; font-size: 0.95rem; }
    .row-list { list-style: none; padding-left: 0; margin: 0.4rem 0; }
    .row-list li { padding: 0.15rem 0; }
    .link-btn { background: none; border: none; color: #5a2090; cursor: pointer; padding: 0; text-align: left; }
    .link-btn.active { font-weight: 600; text-decoration: underline; }
    fieldset { border: 1px dashed #b88adb; border-radius: 4px; padding: 0.5rem; margin: 0.6rem 0; }
    fieldset legend { padding: 0 0.3rem; font-size: 0.85rem; color: #5a2090; }
    label { display: block; font-size: 0.85rem; margin: 0.3rem 0 0.6rem; color: #5a2090; }
    label input, label textarea, label select { width: 100%; padding: 0.3rem; border-radius: 3px; border: 1px solid #c8a4e0; }
    .primary-btn, .btn { padding: 0.4rem 0.9rem; border-radius: 4px; border: 1px solid #5a2090; background: #e9d6f7; cursor: pointer; }
    .primary-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .field-error { color: #8b1f1f; font-size: 0.8rem; margin: 0.2rem 0 0.4rem; }
    .error-banner { background: #f9d5d5; color: #8b1f1f; padding: 0.6rem 1rem; border-radius: 4px; margin-top: 0.6rem; }
    .success-banner { background: #d5f9e0; color: #1f6b3a; padding: 0.6rem 1rem; border-radius: 4px; margin-top: 0.6rem; }
    .build-progress progress { width: 100%; height: 0.6rem; }
    .build-progress pre { background: #2c1140; color: #e8d8ff; padding: 0.4rem; border-radius: 3px; font-size: 0.7rem; max-height: 8rem; overflow: auto; }
    .hint { font-size: 0.75rem; color: #5a2090; margin: 0.2rem 0 0.4rem; }
    .asset-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr)); gap: 0.5rem; }
    .asset-card { display: flex; flex-direction: column; gap: 0.15rem; background: #fff; border: 1px solid #c8a4e0; border-radius: 4px; padding: 0.4rem; font-size: 0.8rem; }
    .asset-thumb { width: 100%; height: 4rem; object-fit: contain; border-radius: 3px; }
  `],
})
export class VnMakerRemoteComponent implements OnInit {
  private http = inject(HttpClient);
  private destroy$ = new Subject<void>();
  readonly assetKinds = ASSET_KINDS;
  readonly assetThemes = ASSET_THEMES;

  errorBanner = '';
  successBanner = '';
  projects$: Observable<VnProject[]> = of([]);
  selectedProject: VnProject | null = null;
  acts: VnAct[] = [];
  selectedAct: VnAct | null = null;
  characters: VnCharacter[] = [];
  backgrounds: VnBackground[] = [];
  scenes: VnScene[] = [];
  selectedScene: VnScene | null = null;
  lines: VnLine[] = [];
  assets: VnAsset[] = [];
  assetUrls: Record<string, string> = {};
  assetFile: File | null = null;
  newProj: Partial<VnProject> = {};
  newChar: Partial<VnCharacter> = { color: '#ffffff' };
  newBackground: Partial<VnBackground> = {};
  newAct: Partial<VnAct> = {};
  newScene: Partial<VnScene> = { entryPoint: false };
  newLine: Partial<VnLine> = { lineIndex: 0 };
  newChoice: Partial<VnChoice> = {};
  newAsset: Partial<VnAsset> = {};
  genAsset: { slug?: string; kind?: string; theme?: string } = { kind: 'background' };
  submitted = { project: false, character: false, background: false, act: false, scene: false, line: false, choice: false, asset: false };
  buildTargetSlug = '';
  buildInFlight = false;
  generating = false;
  currentBuild: VnBuildJob | null = null;
  /** When true the in-studio web VN player overlays the editor for the selected project. */
  previewing = false;

  ngOnInit(): void { this.refreshProjects(); }

  /** Open the live VN preview/player overlay for the selected project. */
  openPreview(): void { if (this.selectedProject) this.previewing = true; }

  /** Close the preview and return to the editor. */
  closePreview(): void { this.previewing = false; }

  refreshProjects(): void {
    this.errorBanner = ''; this.successBanner = '';
    this.projects$ = this.http.get<VnProject[]>('/api/vn/projects').pipe(
      catchError(err => { this.errorBanner = this.fmt(err, 'load projects'); return of([]); }),
    );
  }

  slugOk(s: string | undefined): boolean { return !!s && SLUG_RE.test(s); }
  colorOk(s: string): boolean { return /^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$/.test(s); }
  characterName(id?: string): string { return id ? this.characters.find(c => c.id === id)?.displayName ?? id : 'Narrator'; }

  createProject(): void {
    this.submitted.project = true;
    if (!this.slugOk(this.newProj.slug) || !this.newProj.displayName?.trim()) return;
    this.http.post<VnProject>('/api/vn/projects', this.newProj).subscribe({
      next: p => { this.successBanner = `Project ${p.displayName} created.`; this.newProj = {}; this.submitted.project = false; this.refreshProjects(); },
      error: e => this.errorBanner = this.fmt(e, 'create project'),
    });
  }

  selectProject(p: VnProject): void {
    this.selectedProject = p; this.selectedAct = null; this.selectedScene = null;
    this.previewing = false;
    this.buildTargetSlug = p.slug;
    this.backgrounds = []; this.assets = []; this.assetUrls = {};
    this.http.get<VnAct[]>(`/api/vn/projects/${p.id}/acts`).subscribe({
      next: r => this.acts = r, error: e => this.errorBanner = this.fmt(e, 'load acts'),
    });
    this.http.get<VnCharacter[]>(`/api/vn/projects/${p.id}/characters`).subscribe({
      next: r => this.characters = r, error: e => this.errorBanner = this.fmt(e, 'load characters'),
    });
    this.http.get<VnBackground[]>(`/api/vn/projects/${p.id}/backgrounds`).subscribe({
      next: r => this.backgrounds = r, error: e => this.errorBanner = this.fmt(e, 'load backgrounds'),
    });
    this.http.get<VnScene[]>(`/api/vn/projects/${p.id}/scenes`).subscribe({
      next: r => this.scenes = r, error: e => this.errorBanner = this.fmt(e, 'load scenes'),
    });
    this.http.get<VnAsset[]>(`/api/vn/projects/${p.id}/assets`).subscribe({
      next: a => { this.assets = a; a.forEach(x => this.loadAssetPreview(x)); },
      error: e => this.errorBanner = this.fmt(e, 'load assets'),
    });
  }

  selectAct(a: VnAct): void { this.selectedAct = a; this.selectedScene = null; this.lines = []; }

  selectScene(s: VnScene): void {
    this.selectedScene = s;
    this.newLine.lineIndex = 0;
    this.http.get<VnLine[]>(`/api/vn/projects/${this.selectedProject!.id}/scenes/${s.id}/lines`).subscribe({
      next: ll => { this.lines = ll; this.newLine.lineIndex = ll.length; },
      error: e => this.errorBanner = this.fmt(e, 'load lines'),
    });
  }

  scenesInSelectedAct(): VnScene[] {
    return this.selectedAct ? this.scenes.filter(s => s.actId === this.selectedAct!.id) : [];
  }

  createCharacter(): void {
    if (!this.selectedProject) return;
    this.submitted.character = true;
    if (!this.slugOk(this.newChar.slug) || !this.newChar.displayName?.trim()) return;
    if (this.newChar.color && !this.colorOk(this.newChar.color)) return;
    this.http.post<VnCharacter>(`/api/vn/projects/${this.selectedProject.id}/characters`, this.newChar).subscribe({
      next: c => { this.characters = [...this.characters, c]; this.newChar = { color: '#ffffff' }; this.submitted.character = false; this.successBanner = 'Character added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create character'),
    });
  }

  createBackground(): void {
    if (!this.selectedProject) return;
    this.submitted.background = true;
    if (!this.slugOk(this.newBackground.slug) || !this.newBackground.name?.trim()) return;
    this.http.post<VnBackground>(`/api/vn/projects/${this.selectedProject.id}/backgrounds`, this.newBackground).subscribe({
      next: b => { this.backgrounds = [...this.backgrounds, b]; this.newBackground = {}; this.submitted.background = false; this.successBanner = 'Background added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create background'),
    });
  }

  uploadAsset(): void {
    if (!this.selectedProject || !this.assetFile) return;
    this.submitted.asset = true;
    if (!this.slugOk(this.newAsset.slug) || !this.newAsset.kind?.trim()) return;
    const fd = new FormData();
    fd.append('slug', this.newAsset.slug!);
    fd.append('kind', this.newAsset.kind!);
    fd.append('file', this.assetFile);
    this.http.post<VnAsset>(`/api/vn/projects/${this.selectedProject.id}/assets`, fd).subscribe({
      next: a => {
        this.assets = [a, ...this.assets];
        this.loadAssetPreview(a);
        this.newAsset = {}; this.assetFile = null; this.submitted.asset = false;
        this.successBanner = 'Asset uploaded.';
      },
      error: e => this.errorBanner = this.fmt(e, 'upload asset'),
    });
  }

  generateAsset(): void {
    if (!this.selectedProject) return;
    if (!this.slugOk(this.genAsset.slug) || !this.genAsset.kind) return;
    this.generating = true;
    const body = { slug: this.genAsset.slug, kind: this.genAsset.kind, theme: this.genAsset.theme || null };
    this.http.post<VnAsset>(`/api/vn/projects/${this.selectedProject.id}/assets/generate`, body).subscribe({
      next: a => {
        this.assets = [a, ...this.assets];
        this.loadAssetPreview(a);
        this.genAsset = { kind: this.genAsset.kind }; this.generating = false;
        this.successBanner = 'Art generated.';
      },
      error: e => { this.generating = false; this.errorBanner = this.fmt(e, 'generate art'); },
    });
  }

  isImageAsset(a: VnAsset): boolean { return IMAGE_MIMES.includes(a.mimeType); }

  copySlug(slug: string): void {
    navigator.clipboard?.writeText(slug);
    this.successBanner = `Copied slug "${slug}".`;
  }

  /**
   * Fetch a single asset's bytes and stash an object URL keyed by asset id
   * so the gallery <img> (and, indirectly, the picker labels) can preview
   * image-kind assets. Mirrors the rpg-maker gallery preview.
   */
  private loadAssetPreview(a: VnAsset): void {
    if (!this.selectedProject || !this.isImageAsset(a)) return;
    this.http.get(`/api/vn/projects/${this.selectedProject.id}/assets/${a.id}`,
                  { responseType: 'blob' }).subscribe({
      next: (blob: Blob) => { this.assetUrls = { ...this.assetUrls, [a.id]: URL.createObjectURL(blob) }; },
      error: e => this.errorBanner = this.fmt(e, 'load asset'),
    });
  }

  createAct(): void {
    if (!this.selectedProject) return;
    this.submitted.act = true;
    if (!this.slugOk(this.newAct.slug) || !this.newAct.name?.trim()) return;
    this.http.post<VnAct>(`/api/vn/projects/${this.selectedProject.id}/acts`, this.newAct).subscribe({
      next: a => { this.acts = [...this.acts, a]; this.newAct = {}; this.submitted.act = false; this.successBanner = 'Act added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create act'),
    });
  }

  createScene(): void {
    if (!this.selectedProject || !this.selectedAct) return;
    this.submitted.scene = true;
    if (!this.slugOk(this.newScene.slug) || !this.newScene.name?.trim()) return;
    this.newScene.actId = this.selectedAct.id;
    this.http.post<VnScene>(`/api/vn/projects/${this.selectedProject.id}/scenes`, this.newScene).subscribe({
      next: s => { this.scenes = [...this.scenes, s]; this.newScene = { entryPoint: false }; this.submitted.scene = false; this.successBanner = 'Scene added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create scene'),
    });
  }

  createLine(): void {
    if (!this.selectedProject || !this.selectedScene) return;
    this.submitted.line = true;
    if (!this.newLine.text?.trim()) return;
    this.http.post<VnLine>(
      `/api/vn/projects/${this.selectedProject.id}/scenes/${this.selectedScene.id}/lines`,
      this.newLine).subscribe({
        next: l => { this.lines = [...this.lines, l]; this.newLine = { lineIndex: this.lines.length }; this.submitted.line = false; this.successBanner = 'Line added.'; },
        error: e => this.errorBanner = this.fmt(e, 'create line'),
      });
  }

  createChoice(): void {
    if (!this.selectedProject || this.lines.length === 0) return;
    this.submitted.choice = true;
    if (!this.newChoice.label?.trim() || !this.newChoice.targetSceneId) return;
    const lastLineId = this.lines[this.lines.length - 1].id;
    this.http.post<VnChoice>(
      `/api/vn/projects/${this.selectedProject.id}/lines/${lastLineId}/choices`,
      this.newChoice).subscribe({
        next: () => { this.newChoice = {}; this.submitted.choice = false; this.successBanner = 'Choice added.'; },
        error: e => this.errorBanner = this.fmt(e, 'create choice'),
      });
  }

  /**
   * Download the rendered Godot 4 project as a zip the publisher can
   * open in the editor. Re-uploading the produced bundle is the same
   * publisher-console upload flow.
   */
  downloadGodotZip(): void {
    if (!this.selectedProject) return;
    this.errorBanner = ''; this.successBanner = '';
    this.http.get(`/api/vn/projects/${this.selectedProject.id}/godot-zip`,
                  { responseType: 'blob' }).subscribe({
      next: (blob: Blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `${this.selectedProject!.slug}-godot.zip`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        URL.revokeObjectURL(url);
        this.successBanner = 'Godot source downloaded.';
      },
      error: e => this.errorBanner = this.fmt(e, 'download godot zip'),
    });
  }

  enqueueBuild(): void {
    if (!this.selectedProject) return;
    const slug = this.buildTargetSlug || this.selectedProject.slug;
    if (!this.slugOk(slug)) { this.errorBanner = 'invalid target slug'; return; }
    this.buildInFlight = true; this.currentBuild = null;
    this.http.post<VnBuildJob>(`/api/vn/projects/${this.selectedProject.id}/build`, { targetSlug: slug }).subscribe({
      next: j => { this.currentBuild = j; this.pollBuild(j.id); },
      error: e => { this.buildInFlight = false; this.errorBanner = this.fmt(e, 'enqueue build'); },
    });
  }

  private pollBuild(id: string): void {
    timer(0, 2000).pipe(
      takeUntil(this.destroy$),
      switchMap(() => this.http.get<VnBuildJob>(`/api/vn/builds/${id}`).pipe(
        catchError(e => { this.errorBanner = this.fmt(e, 'poll build'); this.buildInFlight = false; return of(null); }))),
    ).subscribe(j => {
      if (!j) { this.destroy$.next(); return; }
      this.currentBuild = j;
      if (j.status === 'DONE' || j.status === 'FAILED') {
        this.buildInFlight = false;
        this.destroy$.next();
        if (j.status === 'DONE') this.successBanner = 'Bundle uploaded successfully.';
      }
    });
  }

  private fmt(err: unknown, action: string): string {
    if (err instanceof HttpErrorResponse) {
      const detail = (err.error && (err.error.message || err.error.detail)) || err.message;
      return `${action} failed (${err.status}): ${detail}`;
    }
    return `${action} failed: ${String(err)}`;
  }
}
