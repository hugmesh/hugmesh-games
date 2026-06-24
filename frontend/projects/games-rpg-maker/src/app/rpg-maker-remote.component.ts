import { Component, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Observable, Subject, of, timer } from 'rxjs';
import { catchError, switchMap, takeUntil } from 'rxjs/operators';

interface RpgProject     { id: string; slug: string; displayName: string; description?: string; ownerCasdoorId?: string; updatedAt?: string; }
interface RpgArea        { id: string; projectId: string; slug: string; name: string; sortOrder: number; gridMode: boolean; }
interface RpgRoom        { id: string; projectId: string; areaId: string; slug: string; name: string; description?: string; environment?: number; x?: number; y?: number; z?: number; gdScript?: string; pauseOnEnter?: boolean; }
interface RpgExit        { id: string; projectId: string; fromRoomId: string; toRoomId: string; direction: string; locked: boolean; }
interface RpgItem        { id: string; projectId: string; slug: string; name: string; description?: string; iconAssetId?: string; gdScript?: string; }
interface RpgNpc         { id: string; projectId: string; slug: string; name: string; defaultRoomId?: string; dialogueText?: string; portraitAssetId?: string; gdScript?: string; }
interface RpgAsset       { id: string; projectId: string; slug: string; kind: string; mimeType: string; byteSize: number; storagePath?: string; uploadedBy?: string; uploadedAt?: string; }
interface RpgBuildJob    { id: string; status: string; progressPct: number; logTail: string; targetSlug: string; errorMessage?: string; }

const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
const DIRECTIONS = ['N','S','E','W','NE','NW','SE','SW','UP','DOWN','TELEPORT'] as const;
const ASSET_KINDS = ['background','sprite','tileset','portrait','audio'] as const;
const IMAGE_MIMES = ['image/png','image/jpeg','image/webp','image/svg+xml'];

/**
 * Mudlet-shaped editor remote for the snapdragon RPG builder.
 *
 * Three-pane layout:
 *   1. Projects    — pick / create with slug + name validation
 *   2. Areas       — chapter list per selected project
 *   3. Rooms+Exits — per area; exits use the 6+1-direction set Mudlet's
 *                    map editor speaks. Each form surfaces inline error
 *                    messages on bad input (slug shape, missing name,
 *                    duplicate direction) so the publisher knows exactly
 *                    what to fix without opening dev tools.
 *
 * Build action enqueues a BuildJob and polls /api/rpg/builds/{id} every
 * 2 s for progress; the bar shows status + percentage and the most
 * recent log line. On DONE the produced bundle is already federated
 * under the publisher's user via the BFF upload path.
 */
@Component({
  selector: 'app-rpg-maker-remote',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="rpg-maker">
      <header>
        <h1>&#9670; RPG Maker</h1>
        <button class="btn" (click)="refreshProjects()">&#8635; Refresh</button>
      </header>

      @if (errorBanner) { <p class="error-banner" role="alert" aria-live="assertive">{{ errorBanner }}</p> }
      @if (successBanner) { <p class="success-banner" role="status">{{ successBanner }}</p> }

      <div class="three-pane">
        <!-- Projects pane -->
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
              <input name="newProjSlug" [(ngModel)]="newProj.slug" placeholder="my-rpg" />
            </label>
            @if (newProj.slug && !slugOk(newProj.slug)) {
              <p class="field-error" role="alert">slug must be lowercase alphanumeric (with dashes), starting with a letter or digit.</p>
            }
            <label>Display name
              <input name="newProjName" [(ngModel)]="newProj.displayName" />
            </label>
            @if ((submitted.project || slugOk(newProj.slug)) && !newProj.displayName?.trim()) {
              <p class="field-error" role="alert">display name is required.</p>
            }
            <label>Description (optional)
              <textarea name="newProjDesc" [(ngModel)]="newProj.description" rows="2"></textarea>
            </label>
            <button type="button" class="primary-btn" (click)="createProject()"
                    [disabled]="!slugOk(newProj.slug) || !newProj.displayName?.trim()">Create</button>
          </fieldset>
        </section>

        <!-- Areas + Rooms pane -->
        <section class="pane">
          <h2>{{ selectedProject?.displayName || 'Select a project' }}</h2>
          @if (selectedProject) {
            <h3>Areas (each becomes a chapter)</h3>
            <ul class="row-list">
              @for (a of areas; track a.id) {
                <li>
                  <button class="link-btn" type="button"
                          [class.active]="selectedArea?.id === a.id"
                          (click)="selectArea(a)">{{ a.name }} <small>({{ a.slug }})</small></button>
                </li>
              }
            </ul>
            <fieldset>
              <legend>New area / chapter</legend>
              <label>Slug
                <input name="newAreaSlug" [(ngModel)]="newArea.slug" placeholder="cave" />
              </label>
              @if (newArea.slug && !slugOk(newArea.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Name
                <input name="newAreaName" [(ngModel)]="newArea.name" />
              </label>
              @if (submitted.area && !newArea.name?.trim()) {
                <p class="field-error" role="alert">area name is required.</p>
              }
              <button type="button" class="primary-btn" (click)="createArea()"
                      [disabled]="!slugOk(newArea.slug) || !newArea.name?.trim()">Add area</button>
            </fieldset>

            @if (selectedArea) {
              <h3>Rooms in {{ selectedArea.name }}</h3>
              <ul class="row-list">
                @for (r of roomsInSelectedArea(); track r.id) {
                  <li>
                    <button class="link-btn" type="button"
                            [class.active]="selectedRoom?.id === r.id"
                            (click)="selectRoom(r)">{{ r.name }} <small>({{ r.slug }})</small></button>
                  </li>
                }
              </ul>
              <fieldset>
                <legend>New room</legend>
                <label>Slug
                  <input name="newRoomSlug" [(ngModel)]="newRoom.slug" placeholder="entrance" />
                </label>
                @if (newRoom.slug && !slugOk(newRoom.slug)) {
                  <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
                }
                <label>Name
                  <input name="newRoomName" [(ngModel)]="newRoom.name" />
                </label>
                @if (submitted.room && !newRoom.name?.trim()) {
                  <p class="field-error" role="alert">room name is required.</p>
                }
                <label>Description
                  <textarea name="newRoomDesc" [(ngModel)]="newRoom.description" rows="2"></textarea>
                </label>
                <label>GDScript snippet (optional)
                  <textarea name="newRoomGd" [(ngModel)]="newRoom.gdScript" rows="3"
                            placeholder="# fires once on _ready"></textarea>
                </label>
                <label class="checkbox-row">
                  <input type="checkbox" name="newRoomPause"
                         [(ngModel)]="newRoom.pauseOnEnter" />
                  Pause session when any player enters this room
                  <span class="hint">(BFF fires SESSION_PAUSED on entry; resume requires creator/admin or a RESUME line/script)</span>
                </label>
                <button type="button" class="primary-btn" (click)="createRoom()"
                        [disabled]="!slugOk(newRoom.slug) || !newRoom.name?.trim()">Add room</button>
              </fieldset>
            }
          }
        </section>

        <!-- Exits + Build pane -->
        <section class="pane">
          <h2>{{ selectedRoom?.name || 'Select a room' }}</h2>
          @if (selectedRoom) {
            <h3>Exits (6+1 directions)</h3>
            <ul class="row-list">
              @for (x of exitsForSelectedRoom(); track x.id) {
                <li>
                  <strong>{{ x.direction }}</strong> &rarr; {{ resolveRoomName(x.toRoomId) }}
                </li>
              }
            </ul>
            <fieldset>
              <legend>New exit</legend>
              <label>Direction
                <select name="newExitDir" [(ngModel)]="newExit.direction">
                  <option value="">— pick —</option>
                  @for (d of directions; track d) { <option [value]="d">{{ d }}</option> }
                </select>
              </label>
              @if (submitted.exit && !newExit.direction) {
                <p class="field-error" role="alert">direction is required.</p>
              }
              @if (submitted.exit && newExit.direction && existingDirections().includes(newExit.direction)) {
                <p class="field-error" role="alert">{{ newExit.direction }} is already taken on this room.</p>
              }
              <label>Target room
                <select name="newExitTo" [(ngModel)]="newExit.toRoomId">
                  <option value="">— pick —</option>
                  @for (r of rooms; track r.id) { <option [value]="r.id">{{ r.name }} ({{ r.slug }})</option> }
                </select>
              </label>
              @if (submitted.exit && !newExit.toRoomId) {
                <p class="field-error" role="alert">target room is required.</p>
              }
              <button type="button" class="primary-btn" (click)="createExit()"
                      [disabled]="!newExit.direction || !newExit.toRoomId || existingDirections().includes(newExit.direction)">
                Add exit
              </button>
            </fieldset>
          }

          @if (selectedProject) {
            <h3>Build &amp; publish</h3>
            <fieldset>
              <legend>Compile to a Snapdragon-bridge bundle</legend>
              <label>Target slug (where it ships)
                <input name="targetSlug" [(ngModel)]="buildTargetSlug"
                       placeholder="defaults to project slug" />
              </label>
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

        <!-- Items pane -->
        <section class="pane">
          <h2>Items</h2>
          @if (selectedProject) {
            <ul class="row-list">
              @for (it of items; track it.id) {
                <li><strong>{{ it.name }}</strong> <small>({{ it.slug }})</small></li>
              }
            </ul>
            <fieldset>
              <legend>New item</legend>
              <label>Slug
                <input name="newItemSlug" [(ngModel)]="newItem.slug" placeholder="potion" />
              </label>
              @if (newItem.slug && !slugOk(newItem.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Name
                <input name="newItemName" [(ngModel)]="newItem.name" />
              </label>
              @if (submitted.item && !newItem.name?.trim()) {
                <p class="field-error" role="alert">item name is required.</p>
              }
              <label>Description (optional)
                <textarea name="newItemDesc" [(ngModel)]="newItem.description" rows="2"></textarea>
              </label>
              <label>Icon asset
                <select name="newItemIcon" [(ngModel)]="newItem.iconAssetId">
                  <option value="">— none —</option>
                  @for (a of assets; track a.id) { <option [value]="a.id">{{ a.slug }} ({{ a.kind }})</option> }
                </select>
              </label>
              <label>GDScript snippet (optional)
                <textarea name="newItemGd" [(ngModel)]="newItem.gdScript" rows="3"
                          placeholder="# on use"></textarea>
              </label>
              <button type="button" class="primary-btn" (click)="createItem()"
                      [disabled]="!slugOk(newItem.slug) || !newItem.name?.trim()">Add item</button>
            </fieldset>
          }
        </section>

        <!-- NPCs pane -->
        <section class="pane">
          <h2>NPCs</h2>
          @if (selectedProject) {
            <ul class="row-list">
              @for (n of npcs; track n.id) {
                <li><strong>{{ n.name }}</strong> <small>({{ n.slug }})</small></li>
              }
            </ul>
            <fieldset>
              <legend>New NPC</legend>
              <label>Slug
                <input name="newNpcSlug" [(ngModel)]="newNpc.slug" placeholder="shopkeeper" />
              </label>
              @if (newNpc.slug && !slugOk(newNpc.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Name
                <input name="newNpcName" [(ngModel)]="newNpc.name" />
              </label>
              @if (submitted.npc && !newNpc.name?.trim()) {
                <p class="field-error" role="alert">npc name is required.</p>
              }
              <label>Default room
                <select name="newNpcRoom" [(ngModel)]="newNpc.defaultRoomId">
                  <option value="">— none —</option>
                  @for (r of rooms; track r.id) { <option [value]="r.id">{{ r.name }} ({{ r.slug }})</option> }
                </select>
              </label>
              <label>Dialogue (optional)
                <textarea name="newNpcDialogue" [(ngModel)]="newNpc.dialogueText" rows="2"></textarea>
              </label>
              <label>Portrait asset
                <select name="newNpcPortrait" [(ngModel)]="newNpc.portraitAssetId">
                  <option value="">— none —</option>
                  @for (a of assets; track a.id) { <option [value]="a.id">{{ a.slug }} ({{ a.kind }})</option> }
                </select>
              </label>
              <label>GDScript snippet (optional)
                <textarea name="newNpcGd" [(ngModel)]="newNpc.gdScript" rows="3"
                          placeholder="# on interact"></textarea>
              </label>
              <button type="button" class="primary-btn" (click)="createNpc()"
                      [disabled]="!slugOk(newNpc.slug) || !newNpc.name?.trim()">Add NPC</button>
            </fieldset>
          }
        </section>

        <!-- Art assets pane -->
        <section class="pane">
          <h2>Art assets</h2>
          @if (selectedProject) {
            <fieldset>
              <legend>Upload asset</legend>
              <label>Slug
                <input name="newAssetSlug" [(ngModel)]="newAsset.slug" placeholder="cave-bg" />
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

            <h3>Gallery <small>(this session's uploads)</small></h3>
            <ul class="row-list asset-grid">
              @for (a of assets; track a.id) {
                <li class="asset-card">
                  @if (isImageAsset(a)) {
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
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    .rpg-maker { padding: 1rem; font-family: system-ui, sans-serif; }
    header { display: flex; justify-content: space-between; align-items: center; }
    h1 { margin: 0; font-size: 1.4rem; }
    .three-pane { display: grid; grid-template-columns: 1fr 1.4fr 1.4fr; gap: 1rem; margin-top: 1rem; }
    .pane { background: #ecf3ff; border: 1px solid #8aa8d8; border-radius: 6px; padding: 0.8rem; }
    .pane h2 { margin: 0 0 0.4rem; font-size: 1.05rem; }
    .pane h3 { margin: 0.6rem 0 0.3rem; font-size: 0.95rem; }
    .row-list { list-style: none; padding-left: 0; margin: 0.4rem 0; }
    .row-list li { padding: 0.15rem 0; }
    .link-btn { background: none; border: none; color: #20448c; cursor: pointer; padding: 0; text-align: left; }
    .link-btn.active { font-weight: 600; text-decoration: underline; }
    fieldset { border: 1px dashed #8aa8d8; border-radius: 4px; padding: 0.5rem; margin: 0.6rem 0; }
    fieldset legend { padding: 0 0.3rem; font-size: 0.85rem; color: #20448c; }
    label { display: block; font-size: 0.85rem; margin: 0.3rem 0 0.6rem; color: #20448c; }
    label input, label textarea, label select { width: 100%; padding: 0.3rem; border-radius: 3px; border: 1px solid #aabddf; }
    .primary-btn, .btn { padding: 0.4rem 0.9rem; border-radius: 4px; border: 1px solid #20448c; background: #cfe0ff; cursor: pointer; }
    .primary-btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .field-error { color: #8b1f1f; font-size: 0.8rem; margin: 0.2rem 0 0.4rem; }
    .error-banner { background: #f9d5d5; color: #8b1f1f; padding: 0.6rem 1rem; border-radius: 4px; margin-top: 0.6rem; }
    .success-banner { background: #d5f9e0; color: #1f6b3a; padding: 0.6rem 1rem; border-radius: 4px; margin-top: 0.6rem; }
    .build-progress { margin-top: 0.6rem; }
    .build-progress progress { width: 100%; height: 0.6rem; }
    .build-progress pre { background: #15294a; color: #d8e3ff; padding: 0.4rem; border-radius: 3px; font-size: 0.7rem; max-height: 8rem; overflow: auto; }
    .hint { font-size: 0.75rem; color: #20448c; margin: 0.2rem 0 0.4rem; }
    .asset-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(7rem, 1fr)); gap: 0.5rem; }
    .asset-card { display: flex; flex-direction: column; gap: 0.15rem; background: #fff; border: 1px solid #aabddf; border-radius: 4px; padding: 0.4rem; font-size: 0.8rem; }
    .asset-thumb { width: 100%; height: 4rem; object-fit: contain; border-radius: 3px; }
  `],
})
export class RpgMakerRemoteComponent implements OnInit {
  private http = inject(HttpClient);
  private destroy$ = new Subject<void>();
  readonly directions = DIRECTIONS;
  readonly assetKinds = ASSET_KINDS;

  errorBanner = '';
  successBanner = '';
  projects$: Observable<RpgProject[]> = of([]);
  selectedProject: RpgProject | null = null;
  areas: RpgArea[] = [];
  selectedArea: RpgArea | null = null;
  rooms: RpgRoom[] = [];
  selectedRoom: RpgRoom | null = null;
  exits: RpgExit[] = [];
  items: RpgItem[] = [];
  npcs: RpgNpc[] = [];
  assets: RpgAsset[] = [];
  assetUrls: Record<string, string> = {};
  assetFile: File | null = null;
  newProj: Partial<RpgProject> = {};
  newArea: Partial<RpgArea> = {};
  newRoom: Partial<RpgRoom> = {};
  newExit: Partial<RpgExit> = {};
  newItem: Partial<RpgItem> = {};
  newNpc: Partial<RpgNpc> = {};
  newAsset: Partial<RpgAsset> = {};
  submitted = { project: false, area: false, room: false, exit: false, item: false, npc: false, asset: false };
  buildTargetSlug = '';
  buildInFlight = false;
  currentBuild: RpgBuildJob | null = null;

  ngOnInit(): void { this.refreshProjects(); }

  refreshProjects(): void {
    this.errorBanner = ''; this.successBanner = '';
    this.projects$ = this.http.get<RpgProject[]>('/api/rpg/projects').pipe(
      catchError(err => { this.errorBanner = this.fmt(err, 'load projects'); return of([]); }),
    );
  }

  slugOk(s: string | undefined): boolean { return !!s && SLUG_RE.test(s); }

  createProject(): void {
    this.submitted.project = true;
    if (!this.slugOk(this.newProj.slug) || !this.newProj.displayName?.trim()) return;
    this.http.post<RpgProject>('/api/rpg/projects', this.newProj).subscribe({
      next: p => { this.successBanner = `Project ${p.displayName} created.`; this.newProj = {}; this.submitted.project = false; this.refreshProjects(); },
      error: e => this.errorBanner = this.fmt(e, 'create project'),
    });
  }

  selectProject(p: RpgProject): void {
    this.selectedProject = p; this.selectedArea = null; this.selectedRoom = null;
    this.buildTargetSlug = p.slug;
    this.items = []; this.npcs = []; this.assets = []; this.assetUrls = {};
    this.http.get<RpgArea[]>(`/api/rpg/projects/${p.id}/areas`).subscribe({
      next: a => this.areas = a,
      error: e => this.errorBanner = this.fmt(e, 'load areas'),
    });
    this.http.get<RpgRoom[]>(`/api/rpg/projects/${p.id}/rooms`).subscribe({
      next: r => this.rooms = r,
      error: e => this.errorBanner = this.fmt(e, 'load rooms'),
    });
    this.http.get<RpgExit[]>(`/api/rpg/projects/${p.id}/exits`).subscribe({
      next: x => this.exits = x,
      error: e => this.errorBanner = this.fmt(e, 'load exits'),
    });
    this.http.get<RpgItem[]>(`/api/rpg/projects/${p.id}/items`).subscribe({
      next: it => this.items = it,
      error: e => this.errorBanner = this.fmt(e, 'load items'),
    });
    this.http.get<RpgNpc[]>(`/api/rpg/projects/${p.id}/npcs`).subscribe({
      next: n => this.npcs = n,
      error: e => this.errorBanner = this.fmt(e, 'load npcs'),
    });
    this.http.get<RpgAsset[]>(`/api/rpg/projects/${p.id}/assets`).subscribe({
      next: a => { this.assets = a; a.forEach(x => this.loadAssetPreview(x)); },
      error: e => this.errorBanner = this.fmt(e, 'load assets'),
    });
  }

  selectArea(a: RpgArea): void {
    this.selectedArea = a; this.selectedRoom = null;
  }

  selectRoom(r: RpgRoom): void {
    this.selectedRoom = r;
    this.newExit.fromRoomId = r.id;
  }

  roomsInSelectedArea(): RpgRoom[] {
    return this.selectedArea ? this.rooms.filter(r => r.areaId === this.selectedArea!.id) : [];
  }

  exitsForSelectedRoom(): RpgExit[] {
    return this.selectedRoom ? this.exits.filter(x => x.fromRoomId === this.selectedRoom!.id) : [];
  }

  existingDirections(): string[] {
    return this.exitsForSelectedRoom().map(x => x.direction);
  }

  resolveRoomName(id: string): string {
    return this.rooms.find(r => r.id === id)?.name ?? id;
  }

  createArea(): void {
    if (!this.selectedProject) return;
    this.submitted.area = true;
    if (!this.slugOk(this.newArea.slug) || !this.newArea.name?.trim()) return;
    this.http.post<RpgArea>(`/api/rpg/projects/${this.selectedProject.id}/areas`, this.newArea).subscribe({
      next: a => { this.areas = [...this.areas, a]; this.newArea = {}; this.submitted.area = false; this.successBanner = 'Area added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create area'),
    });
  }

  createRoom(): void {
    if (!this.selectedProject || !this.selectedArea) return;
    this.submitted.room = true;
    if (!this.slugOk(this.newRoom.slug) || !this.newRoom.name?.trim()) return;
    this.newRoom.areaId = this.selectedArea.id;
    this.http.post<RpgRoom>(`/api/rpg/projects/${this.selectedProject.id}/rooms`, this.newRoom).subscribe({
      next: r => { this.rooms = [...this.rooms, r]; this.newRoom = {}; this.submitted.room = false; this.successBanner = 'Room added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create room'),
    });
  }

  createExit(): void {
    if (!this.selectedProject || !this.selectedRoom) return;
    this.submitted.exit = true;
    if (!this.newExit.direction || !this.newExit.toRoomId) return;
    if (this.existingDirections().includes(this.newExit.direction!)) return;
    this.newExit.fromRoomId = this.selectedRoom.id;
    this.http.post<RpgExit>(`/api/rpg/projects/${this.selectedProject.id}/exits`, this.newExit).subscribe({
      next: x => { this.exits = [...this.exits, x]; this.newExit = {}; this.submitted.exit = false; this.successBanner = 'Exit added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create exit'),
    });
  }

  createItem(): void {
    if (!this.selectedProject) return;
    this.submitted.item = true;
    if (!this.slugOk(this.newItem.slug) || !this.newItem.name?.trim()) return;
    this.http.post<RpgItem>(`/api/rpg/projects/${this.selectedProject.id}/items`, this.newItem).subscribe({
      next: it => { this.items = [...this.items, it]; this.newItem = {}; this.submitted.item = false; this.successBanner = 'Item added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create item'),
    });
  }

  createNpc(): void {
    if (!this.selectedProject) return;
    this.submitted.npc = true;
    if (!this.slugOk(this.newNpc.slug) || !this.newNpc.name?.trim()) return;
    this.http.post<RpgNpc>(`/api/rpg/projects/${this.selectedProject.id}/npcs`, this.newNpc).subscribe({
      next: n => { this.npcs = [...this.npcs, n]; this.newNpc = {}; this.submitted.npc = false; this.successBanner = 'NPC added.'; },
      error: e => this.errorBanner = this.fmt(e, 'create npc'),
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
    this.http.post<RpgAsset>(`/api/rpg/projects/${this.selectedProject.id}/assets`, fd).subscribe({
      next: a => {
        this.assets = [...this.assets, a];
        this.loadAssetPreview(a);
        this.newAsset = {}; this.assetFile = null; this.submitted.asset = false;
        this.successBanner = 'Asset uploaded.';
      },
      error: e => this.errorBanner = this.fmt(e, 'upload asset'),
    });
  }

  isImageAsset(a: RpgAsset): boolean {
    return IMAGE_MIMES.includes(a.mimeType);
  }

  copySlug(slug: string): void {
    navigator.clipboard?.writeText(slug);
    this.successBanner = `Copied slug "${slug}".`;
  }

  /**
   * Fetch a single asset's bytes (no list endpoint serves images inline)
   * and stash an object URL keyed by asset id so the gallery <img> can
   * render the thumbnail for image-kind assets.
   */
  private loadAssetPreview(a: RpgAsset): void {
    if (!this.selectedProject || !this.isImageAsset(a)) return;
    this.http.get(`/api/rpg/projects/${this.selectedProject.id}/assets/${a.id}`,
                  { responseType: 'blob' }).subscribe({
      next: (blob: Blob) => { this.assetUrls = { ...this.assetUrls, [a.id]: URL.createObjectURL(blob) }; },
      error: e => this.errorBanner = this.fmt(e, 'load asset'),
    });
  }

  /**
   * Download the rendered Godot 4 project as a zip the publisher can
   * open directly in the editor — useful when the maker UI doesn't
   * surface fine-grained art / scene tweaks. Re-uploading the bundle
   * after editing is just the normal /api/admin/games/upload path.
   */
  downloadGodotZip(): void {
    if (!this.selectedProject) return;
    this.errorBanner = ''; this.successBanner = '';
    this.http.get(`/api/rpg/projects/${this.selectedProject.id}/godot-zip`,
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
    this.http.post<RpgBuildJob>(`/api/rpg/projects/${this.selectedProject.id}/build`, { targetSlug: slug }).subscribe({
      next: j => { this.currentBuild = j; this.pollBuild(j.id); },
      error: e => { this.buildInFlight = false; this.errorBanner = this.fmt(e, 'enqueue build'); },
    });
  }

  private pollBuild(id: string): void {
    timer(0, 2000).pipe(
      takeUntil(this.destroy$),
      switchMap(() => this.http.get<RpgBuildJob>(`/api/rpg/builds/${id}`).pipe(
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
