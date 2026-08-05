import { Component, OnDestroy, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { EMPTY, Observable, Subject, of, timer } from 'rxjs';
import { catchError, debounceTime, switchMap, takeUntil } from 'rxjs/operators';

interface RpgProject     { id: string; slug: string; displayName: string; description?: string; ownerCasdoorId?: string; updatedAt?: string; }
interface RpgArea        { id: string; projectId: string; slug: string; name: string; sortOrder: number; gridMode: boolean; theme?: string; }
interface RpgRoom        { id: string; projectId: string; areaId: string; slug: string; name: string; description?: string; environment?: number; x?: number; y?: number; z?: number; gdScript?: string; pauseOnEnter?: boolean; theme?: string | null; buildingId?: string | null; floorLevel?: number | null; }
interface RpgExit        { id: string; projectId: string; fromRoomId: string; toRoomId: string; direction: string; locked: boolean; }
interface RpgItem        { id: string; projectId: string; slug: string; name: string; description?: string; iconAssetId?: string; gdScript?: string; }
interface RpgNpc         { id: string; projectId: string; slug: string; name: string; defaultRoomId?: string; dialogueText?: string; portraitAssetId?: string; gdScript?: string; }
interface RpgAsset       { id: string; projectId: string; slug: string; kind: string; mimeType: string; byteSize: number; storagePath?: string; uploadedBy?: string; uploadedAt?: string; }
interface RpgBuildJob    { id: string; status: string; progressPct: number; logTail: string; targetSlug: string; errorMessage?: string; }
// FF-style town buildings placed on a per-host-room GRIDxGRID grid; the
// side service auto-creates interior floor rooms + UP/DOWN stair exits.
interface RpgBuilding    { id: string; projectId: string; areaId: string; hostRoomId: string; slug: string; name: string; kind: string; gridX: number; gridY: number; width: number; depth: number; floors: number; basements: number; }
interface RpgShopStock   { id: string; projectId: string; buildingId: string; itemId: string; buyPrice: number; sellPrice: number; stockQty: number; }
/** The 12 proportion columns. Always `<CharacterSpec key>Scale` — the rule
 *  `CharacterSpec.wireName(key)` encodes on the Java side. */
type ScaleField =
  'heightScale' | 'headScale' | 'neckScale' | 'chestScale' | 'waistScale' | 'shoulderScale' |
  'armLengthScale' | 'armThicknessScale' | 'legLengthScale' | 'legThicknessScale' |
  'handScale' | 'footScale';
/** The 7 colour columns. `headColor` is the SKIN tone — a historic column
 *  name that predates the slot vocabulary and is deliberately NOT renamed. */
type ColorField =
  'bodyColor' | 'headColor' | 'hatColor' | 'hairColor' | 'accentColor' | 'itemColor' | 'eyeColor';
// One parametric humanoid hero per project; every field is a whitelisted
// token, a bounded proportion, or a #rrggbb colour — mirrors
// rpg_avatar_config's DB CHECKs, AvatarConfigService and CharacterSpec on
// the side service. id is null until first save (the GET synthesises a
// non-persisted default so the composer always has something to render).
interface RpgAvatarConfig extends Record<ScaleField, number>, Record<ColorField, string> {
  id?: string | null; projectId?: string;
  renderStyle: string; bodyShape: string; headShape: string; hairStyle: string; outfitType: string;
  hatType: string; heldMain: string; heldOff: string; backItem: string; faceItem: string;
  skinTextureAssetId?: string | null;
}
/** A tunable proportion: `CharacterSpec.Bound` plus its wire field name. */
interface Proportion { key: string; wire: ScaleField; min: number; max: number; dflt: number; label: string; }
/** A recolourable region: `CharacterSpec.ColorSlot` plus its wire field name. */
interface ColorSlotDef { key: string; wire: ColorField; dflt: string; label: string; }

const SLUG_RE = /^[a-z0-9][a-z0-9-]{0,63}$/;
const DIRECTIONS = ['N','S','E','W','NE','NW','SE','SW','UP','DOWN','TELEPORT'] as const;
const ASSET_KINDS = ['background','sprite','tileset','portrait','audio'] as const;
// Kinds the server-side procedural generator can draw house-style art for.
const GENERATE_KINDS = ['tileset','sprite','portrait','icon'] as const;
const IMAGE_MIMES = ['image/png','image/jpeg','image/webp','image/svg+xml'];
// Biome themes (RpgArea.theme). 'plain' is the pre-theme default; 'town'
// is the one that unlocks the Buildings pane. Mirrors the server whitelist.
const THEMES = ['plain','desert','forest','dungeon','cave','town','castle'] as const;
// Building kinds + the shop-capable subset — kept in lockstep with
// RpgBuildingService.KINDS / SHOP_KINDS on the side service.
const BUILDING_KINDS = ['item_shop','weapon_shop','armor_shop','magic_shop','accessory_shop','inn','tavern','church','house'] as const;
const SHOP_KINDS = new Set(['item_shop','weapon_shop','armor_shop','magic_shop','accessory_shop','inn','tavern']);
// Placement grid is GRID x GRID cells per host room (server GRID = 10).
const GRID = 10;
const FOOTPRINT_SIZES = [1, 2, 3] as const;
const FLOOR_CHOICES = [1, 2, 3, 4, 5] as const;
const BASEMENT_CHOICES = [0, 1, 2, 3] as const;
// ── Parametric humanoid hero (decision 028) ─────────────────────────────────
// These mirror games.hugmesh.character.CharacterSpec exactly. GROWING A
// WHITELIST MOVES FIVE THINGS TOGETHER, and every one of them is tested:
//   1. CharacterSpec          — the canonical token set / Bound / ColorSlot
//   2. the Liquibase changeset — rpg-builder 009+, append-only: drop and
//                                re-add the column CHECK with the new member
//   3. HumanoidRig            — the geometry arm that actually builds it
//   4. the const below        — what this maker knows exists
//   5. the control below      — the <select>/<input> that exposes it
// avatar_factory.gd is DELIBERATELY NOT one of them: since 028 it walks the
// rig GdRigBaker bakes in Java, so GDScript never re-derives a dimension and
// 2D/3D parity is structural rather than a review obligation.
const RENDER_STYLES = ['smooth', 'lowpoly'] as const;
const BODY_SHAPES   = ['slim', 'stocky', 'round'] as const;
const HEAD_SHAPES   = ['round', 'square', 'oval', 'tapered'] as const;
const HAIR_STYLES   = ['none', 'short', 'long', 'ponytail', 'spiky', 'bun', 'braid'] as const;
const OUTFIT_TYPES  = ['robe', 'tunic', 'armor', 'dress', 'jumpsuit', 'bare'] as const;
const HAT_TYPES     = ['none', 'cap', 'cone', 'top_hat', 'helm', 'crown', 'hood', 'bandana', 'straw'] as const;
const HELD_MAIN     = ['none', 'sword', 'staff', 'axe', 'bow', 'wand', 'pickaxe', 'lantern', 'book'] as const;
const HELD_OFF      = ['none', 'shield', 'torch', 'book', 'lantern', 'orb'] as const;
const BACK_ITEMS    = ['none', 'cape', 'backpack', 'wings', 'quiver', 'scabbard'] as const;
const FACE_ITEMS    = ['none', 'glasses', 'mask', 'visor', 'eyepatch'] as const;
// Render-time face pose, never saved geometry — so it drives the preview
// request and is absent from the PUT payload.
const EXPRESSIONS   = ['neutral', 'happy', 'sad', 'angry', 'surprised',
                       'wink', 'sleepy', 'determined', 'shout'] as const;
/** The humanoid bounds — CharacterSpec.PROPORTIONS, same order. */
const PROPORTIONS: readonly Proportion[] = [
  { key: 'height',       wire: 'heightScale',       min: 0.70, max: 1.40, dflt: 1.00, label: 'Height' },
  { key: 'head',         wire: 'headScale',         min: 0.70, max: 1.50, dflt: 1.00, label: 'Head size' },
  { key: 'neck',         wire: 'neckScale',         min: 0.60, max: 1.60, dflt: 1.00, label: 'Neck' },
  { key: 'chest',        wire: 'chestScale',        min: 0.70, max: 1.40, dflt: 1.00, label: 'Chest' },
  { key: 'waist',        wire: 'waistScale',        min: 0.60, max: 1.50, dflt: 1.00, label: 'Waist' },
  { key: 'shoulder',     wire: 'shoulderScale',     min: 0.70, max: 1.40, dflt: 1.00, label: 'Shoulders' },
  { key: 'armLength',    wire: 'armLengthScale',    min: 0.75, max: 1.30, dflt: 1.00, label: 'Arm length' },
  { key: 'armThickness', wire: 'armThicknessScale', min: 0.60, max: 1.50, dflt: 1.00, label: 'Arm thickness' },
  { key: 'legLength',    wire: 'legLengthScale',    min: 0.75, max: 1.30, dflt: 1.00, label: 'Leg length' },
  { key: 'legThickness', wire: 'legThicknessScale', min: 0.60, max: 1.50, dflt: 1.00, label: 'Leg thickness' },
  { key: 'hand',         wire: 'handScale',         min: 0.60, max: 1.60, dflt: 1.00, label: 'Hands' },
  { key: 'foot',         wire: 'footScale',         min: 0.60, max: 1.60, dflt: 1.00, label: 'Feet' },
];
/** CharacterSpec.COLORS — note `skin` writes the historic `head_color`. */
const COLOR_SLOTS: readonly ColorSlotDef[] = [
  { key: 'body',   wire: 'bodyColor',   dflt: '#3f59b3', label: 'Outfit' },
  { key: 'skin',   wire: 'headColor',   dflt: '#e6c8a0', label: 'Skin' },
  { key: 'hat',    wire: 'hatColor',    dflt: '#202020', label: 'Hat' },
  { key: 'hair',   wire: 'hairColor',   dflt: '#3a2a1c', label: 'Hair' },
  { key: 'accent', wire: 'accentColor', dflt: '#d8b45a', label: 'Trim' },
  { key: 'item',   wire: 'itemColor',   dflt: '#8a8f98', label: 'Items' },
  { key: 'eye',    wire: 'eyeColor',    dflt: '#2b2b33', label: 'Eyes' },
];
// Dragging a slider fires an ngModelChange per pixel; debounce so the render
// service sees ONE request per gesture instead of forty.
const PREVIEW_DEBOUNCE_MS = 250;
// Within AvatarSvgRenderer's [16, 1024] clamp; the <img> is CSS-sized, this
// is just the SVG viewport the server draws into.
const PREVIEW_W = 256;
const PREVIEW_H = 384;
// Distinct swatch per kind so placed footprints read at a glance.
const KIND_COLORS: Record<string, string> = {
  item_shop: '#c0603a', weapon_shop: '#8b3a3a', armor_shop: '#3a5a8b',
  magic_shop: '#6b3a8b', accessory_shop: '#3a8b7a', inn: '#8b7a3a',
  tavern: '#a06a2a', church: '#5a8b3a', house: '#5a5a6a',
};
// After how many CONSECUTIVE poll failures we give up (RESOLUTION 13):
// a real import+export starves the builder JVM and a lone 5xx/timeout
// must not permanently freeze the progress bar.
const MAX_POLL_ERRORS = 5;

/**
 * Mudlet-shaped editor remote for the hugmesh RPG builder.
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
                  <span class="theme-chip">{{ a.theme || 'plain' }}</span>
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
              <label>Theme
                <select name="newAreaTheme" [(ngModel)]="newArea.theme">
                  @for (t of themes; track t) { <option [value]="t">{{ t }}</option> }
                </select>
              </label>
              <p class="hint">'town' unlocks the Buildings pane for placing FF-style shops &amp; houses.</p>
              <button type="button" class="primary-btn" (click)="createArea()"
                      [disabled]="!slugOk(newArea.slug) || !newArea.name?.trim()">Add area</button>
            </fieldset>

            @if (selectedArea) {
              <h3>Rooms in {{ selectedArea.name }}</h3>
              <ul class="row-list">
                @for (r of roomsInSelectedArea(); track r.id) {
                  <li>
                    @if (r.buildingId) {
                      <!-- Auto-created building interior floor: read-only, no
                           edit/exit affordances (the service rejects writes). -->
                      <span class="room-static">{{ r.name }} <small>({{ r.slug }})</small></span>
                      <span class="floor-badge" title="auto-created building interior">building floor</span>
                    } @else {
                      <button class="link-btn" type="button"
                              [class.active]="selectedRoom?.id === r.id"
                              (click)="selectRoom(r)">{{ r.name }} <small>({{ r.slug }})</small></button>
                    }
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
                <label>Theme override (optional)
                  <select name="newRoomTheme" [(ngModel)]="newRoom.theme">
                    <option [ngValue]="null">— inherit area theme —</option>
                    @for (t of themes; track t) { <option [ngValue]="t">{{ t }}</option> }
                  </select>
                </label>
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
              <legend>Compile to a HugMesh-bridge bundle</legend>
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

        <!-- Characters pane — parametric hero composer (one model drives the
             3D rig, this 2D preview and the export) + NPC portrait roster -->
        @if (selectedProject) {
          <section class="pane characters-pane">
            <h2>Characters</h2>

            <fieldset>
              <legend>Hero character composer</legend>
              <p class="hint">the playable hero every player of this game embodies —
                baked into the export (remote players get a hue-shifted variant).
                The preview comes from the same server-side renderer that draws the
                shipped art, so what you see here is what the game shows.</p>
              <div class="avatar-composer">
                <div class="avatar-preview-wrap">
                  @if (previewSrc) {
                    <img class="avatar-preview" [src]="previewSrc"
                         alt="Hero character preview" />
                  } @else {
                    <p class="avatar-preview avatar-preview-empty" role="status">rendering&hellip;</p>
                  }
                  <!-- aria-hidden: this flips on every slider tick and a live
                       region would machine-gun a screen reader. -->
                  @if (previewPending) { <span class="preview-chip" aria-hidden="true">updating&hellip;</span> }
                  <label>Expression
                    <select name="avatarExpression" [ngModel]="previewExpression"
                            (ngModelChange)="setExpression($event)">
                      @for (e of expressions; track e) { <option [value]="e">{{ e }}</option> }
                    </select>
                  </label>
                  <p class="hint">preview only — an expression is a render-time face
                    pose, never saved geometry.</p>
                  @if (previewError) { <p class="field-error" role="alert">{{ previewError }}</p> }
                </div>

                <div class="avatar-controls">
                  <fieldset class="control-group">
                    <legend>Style</legend>
                    <fieldset class="segmented">
                      <legend>Render style</legend>
                      @for (s of renderStyles; track s) {
                        <label class="radio-row">
                          <input type="radio" name="avatarRenderStyle" [value]="s"
                                 [checked]="avatarConfig.renderStyle === s"
                                 (change)="setRenderStyle(s)" />
                          {{ renderStyleLabel(s) }}
                        </label>
                      }
                    </fieldset>
                    <div class="grid-2">
                      <label>Body shape
                        <select name="avatarBody" [(ngModel)]="avatarConfig.bodyShape"
                                (ngModelChange)="avatarChanged()">
                          @for (s of bodyShapes; track s) { <option [value]="s">{{ s }}</option> }
                        </select>
                      </label>
                      <label>Head shape
                        <select name="avatarHead" [(ngModel)]="avatarConfig.headShape"
                                (ngModelChange)="avatarChanged()">
                          @for (s of headShapes; track s) { <option [value]="s">{{ s }}</option> }
                        </select>
                      </label>
                      <label>Hair
                        <select name="avatarHair" [(ngModel)]="avatarConfig.hairStyle"
                                (ngModelChange)="avatarChanged()">
                          @for (s of hairStyles; track s) { <option [value]="s">{{ s }}</option> }
                        </select>
                      </label>
                      <label>Outfit
                        <select name="avatarOutfit" [(ngModel)]="avatarConfig.outfitType"
                                (ngModelChange)="avatarChanged()">
                          @for (s of outfitTypes; track s) { <option [value]="s">{{ s }}</option> }
                        </select>
                      </label>
                    </div>
                  </fieldset>

                  <fieldset class="control-group">
                    <legend>Proportions</legend>
                    <p class="hint">every slider is bounded to stay recognisably
                      humanoid — the server rejects anything outside the range
                      rather than quietly clamping it.</p>
                    <!-- [attr.name], not [name]: with ngModel present a plain
                         [name] binds the DIRECTIVE's input and never reaches
                         the DOM, so the control has no name attribute at all. -->
                    <div class="grid-2">
                      @for (p of proportions; track p.key) {
                        <label>{{ p.label }} <span class="scale-readout">{{ proportionLabel(p) }}</span>
                          <input type="range" [attr.name]="'avatar-' + p.wire"
                                 [min]="p.min" [max]="p.max" step="0.01"
                                 [ngModel]="avatarConfig[p.wire]"
                                 (ngModelChange)="setProportion(p, $event)" />
                        </label>
                      }
                    </div>
                  </fieldset>

                  <fieldset class="control-group">
                    <legend>Items</legend>
                    <div class="grid-2">
                      <label>Hat
                        <select name="avatarHat" [(ngModel)]="avatarConfig.hatType"
                                (ngModelChange)="avatarChanged()">
                          @for (h of hatTypes; track h) { <option [value]="h">{{ h }}</option> }
                        </select>
                      </label>
                      <label>Face item
                        <select name="avatarFaceItem" [(ngModel)]="avatarConfig.faceItem"
                                (ngModelChange)="avatarChanged()">
                          @for (i of faceItems; track i) { <option [value]="i">{{ i }}</option> }
                        </select>
                      </label>
                      <label>Right hand
                        <select name="avatarHeldMain" [(ngModel)]="avatarConfig.heldMain"
                                (ngModelChange)="avatarChanged()">
                          @for (i of heldMain; track i) { <option [value]="i">{{ i }}</option> }
                        </select>
                      </label>
                      <label>Left hand
                        <select name="avatarHeldOff" [(ngModel)]="avatarConfig.heldOff"
                                (ngModelChange)="avatarChanged()">
                          @for (i of heldOff; track i) { <option [value]="i">{{ i }}</option> }
                        </select>
                      </label>
                      <label>Back
                        <select name="avatarBackItem" [(ngModel)]="avatarConfig.backItem"
                                (ngModelChange)="avatarChanged()">
                          @for (i of backItems; track i) { <option [value]="i">{{ i }}</option> }
                        </select>
                      </label>
                    </div>
                  </fieldset>

                  <fieldset class="control-group">
                    <legend>Colours</legend>
                    <div class="color-grid">
                      @for (c of colorSlots; track c.key) {
                        <label>{{ c.label }}
                          <input type="color" [attr.name]="'avatar-' + c.wire"
                                 [ngModel]="avatarConfig[c.wire]"
                                 (ngModelChange)="setColor(c, $event)" />
                        </label>
                      }
                    </div>
                  </fieldset>
                </div>
              </div>

              <div class="btn-row">
                <button type="button" class="primary-btn" (click)="saveAvatarConfig()">Save hero character</button>
                <button type="button" class="btn" (click)="resetAvatarConfig()">Reset to defaults</button>
              </div>
            </fieldset>

            <fieldset>
              <legend>Transfer <small>(portable Character Document)</small></legend>
              <p class="hint">the interchange format other generators, games and
                services read — export it, edit it, bring it back.</p>
              <button type="button" class="primary-btn" (click)="downloadCharacter()">
                &#8595; Download character (.json)
              </button>
              <label>Paste a character document
                <textarea name="avatarImportDoc" [(ngModel)]="importDoc" rows="4"
                          placeholder='&#123;"schemaVersion":1,"kind":"hugmesh.character",&hellip;&#125;'></textarea>
              </label>
              <label>&hellip;or load one from a file
                <input type="file" name="avatarImportFile" accept="application/json,.json"
                       (change)="onImportFile($event)" />
              </label>
              @if (importError) { <p class="field-error" role="alert">{{ importError }}</p> }
              <button type="button" class="primary-btn" (click)="importCharacter()"
                      [disabled]="!importDoc.trim()">Import character</button>
            </fieldset>

            <h3>NPC dialogue portraits (2D)</h3>
            @if (npcs.length === 0) {
              <p class="hint">no NPCs yet — add one in the NPCs pane to give it a portrait.</p>
            }
            <ul class="row-list npc-roster">
              @for (n of npcs; track n.id) {
                <li class="npc-row">
                  @if (n.portraitAssetId && assetUrls[n.portraitAssetId]) {
                    <img class="portrait-thumb" [src]="assetUrls[n.portraitAssetId]"
                         [alt]="n.slug + ' portrait'" />
                  } @else {
                    <span class="portrait-thumb portrait-empty" aria-hidden="true">?</span>
                  }
                  <div class="npc-meta">
                    <strong>{{ n.name }}</strong> <small>({{ n.slug }})</small>
                    <p class="hint">{{ dialogueSnippet(n) }}</p>
                    <label>Portrait
                      <select [name]="'rosterPortrait-' + n.id" [(ngModel)]="n.portraitAssetId">
                        <option value="">— none —</option>
                        @for (a of assets; track a.id) { <option [value]="a.id">{{ a.slug }} ({{ a.kind }})</option> }
                      </select>
                    </label>
                  </div>
                </li>
              }
            </ul>
            <p class="hint">Portraits show in-game when a player walks up to the NPC and
              presses <strong>E</strong>. Assignment persists through the NPC form; this
              roster is the at-a-glance overview.</p>
          </section>
        }

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

            <fieldset>
              <legend>Generate art <small>(no upload needed)</small></legend>
              <p class="hint">makes deterministic house-style placeholder SVG art so a fresh project isn't blank.</p>
              <label>Slug
                <input name="genAssetSlug" [(ngModel)]="genAsset.slug" placeholder="cave-floor" />
              </label>
              @if (genAsset.slug && !slugOk(genAsset.slug)) {
                <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
              }
              <label>Kind
                <select name="genAssetKind" [(ngModel)]="genAsset.kind">
                  <option value="">— pick —</option>
                  @for (k of generateKinds; track k) { <option [value]="k">{{ k }}</option> }
                </select>
              </label>
              @if (submitted.generate && !genAsset.kind?.trim()) {
                <p class="field-error" role="alert">kind is required.</p>
              }
              <label>Theme / seed (optional)
                <input name="genAssetTheme" [(ngModel)]="genAsset.theme" placeholder="stone, grass, red…" />
              </label>
              <button type="button" class="primary-btn" (click)="generateAsset()"
                      [disabled]="generating || !slugOk(genAsset.slug) || !genAsset.kind?.trim()">
                @if (generating) { Generating &hellip; } @else { Generate art }
              </button>
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

        <!-- Buildings pane — only for town-themed areas (D11 / RESOLUTION 9) -->
        @if (selectedArea && selectedArea.theme === 'town') {
          <section class="pane">
            <h2>Buildings in {{ selectedArea.name }}</h2>
            <p class="hint">Place FF-style shops &amp; houses on a {{ grid }}&times;{{ grid }} grid.
              Each building auto-creates its interior floor rooms + stair exits.</p>

            <h3>Host room</h3>
            @if (hostRoomChoices().length) {
              <ul class="row-list">
                @for (r of hostRoomChoices(); track r.id) {
                  <li>
                    <button class="link-btn" type="button"
                            [class.active]="selectedHostRoom?.id === r.id"
                            (click)="selectHostRoom(r)">{{ r.name }} <small>({{ r.slug }})</small></button>
                  </li>
                }
              </ul>
            } @else {
              <p class="hint">add an outdoor room to this town area first
                (rooms that already host a building interior can't host another).</p>
            }

            @if (selectedHostRoom) {
              <fieldset>
                <legend>New building</legend>
                <label>Slug
                  <input name="newBldgSlug" [(ngModel)]="newBuilding.slug" placeholder="item-shop" />
                </label>
                @if (newBuilding.slug && !slugOk(newBuilding.slug)) {
                  <p class="field-error" role="alert">slug must match ^[a-z0-9][a-z0-9-]{{ '{0,63}' }}$.</p>
                }
                <label>Name
                  <input name="newBldgName" [(ngModel)]="newBuilding.name" />
                </label>
                @if (submitted.building && !newBuilding.name?.trim()) {
                  <p class="field-error" role="alert">building name is required.</p>
                }
                <label>Kind
                  <select name="newBldgKind" [(ngModel)]="newBuilding.kind">
                    @for (k of buildingKinds; track k) { <option [value]="k">{{ k }}</option> }
                  </select>
                </label>
                <div class="grid-2">
                  <label>Floors
                    <select name="newBldgFloors" [(ngModel)]="newBuilding.floors">
                      @for (n of floorChoices; track n) { <option [ngValue]="n">{{ n }}</option> }
                    </select>
                  </label>
                  <label>Basements
                    <select name="newBldgBasements" [(ngModel)]="newBuilding.basements">
                      @for (n of basementChoices; track n) { <option [ngValue]="n">{{ n }}</option> }
                    </select>
                  </label>
                  <label>Width
                    <select name="newBldgWidth" [(ngModel)]="newBuilding.width">
                      @for (n of footprintSizes; track n) { <option [ngValue]="n">{{ n }}</option> }
                    </select>
                  </label>
                  <label>Depth
                    <select name="newBldgDepth" [(ngModel)]="newBuilding.depth">
                      @for (n of footprintSizes; track n) { <option [ngValue]="n">{{ n }}</option> }
                    </select>
                  </label>
                </div>
                <p class="hint">fill the form, then click an empty grid cell to place the building there.</p>
                @if (buildingFormError) { <p class="field-error" role="alert">{{ buildingFormError }}</p> }
              </fieldset>

              <h3>Placement &mdash; {{ selectedHostRoom.name }}</h3>
              <div class="place-grid">
                <div class="cell-layer"
                     [style.grid-template-columns]="'repeat(' + grid + ', 1fr)'"
                     [style.grid-template-rows]="'repeat(' + grid + ', 1fr)'">
                  @for (cell of gridCells; track $index) {
                    <button type="button" class="place-cell"
                            [attr.aria-label]="'place at ' + cell.x + ',' + cell.y"
                            (click)="placeAt(cell.x, cell.y)"></button>
                  }
                </div>
                @for (b of buildingsOnHostRoom(); track b.id) {
                  <button type="button" class="bldg-block"
                          [class.selected]="selectedBuilding?.id === b.id"
                          [style.left.%]="b.gridX * cellPct"
                          [style.top.%]="b.gridY * cellPct"
                          [style.width.%]="b.width * cellPct"
                          [style.height.%]="b.depth * cellPct"
                          [style.background]="kindColor(b.kind)"
                          [title]="b.name + ' — ' + b.kind"
                          (click)="selectBuilding(b)">{{ b.name }}</button>
                }
              </div>
            }

            @if (selectedBuilding) {
              <h3>{{ selectedBuilding.name }} <span class="theme-chip">{{ selectedBuilding.kind }}</span></h3>
              @if (isShopKind(selectedBuilding.kind)) {
                <h4>Stock</h4>
                <ul class="row-list">
                  @for (s of stock; track s.id) {
                    <li>
                      <strong>{{ itemName(s.itemId) }}</strong>
                      &mdash; buy {{ s.buyPrice }} / sell {{ s.sellPrice }} /
                      qty {{ s.stockQty === -1 ? '&infin;' : s.stockQty }}
                      <button type="button" class="link-btn" (click)="deleteStock(s)">delete</button>
                    </li>
                  }
                </ul>
                <fieldset>
                  <legend>Add stock</legend>
                  <label>Item
                    <select name="newStockItem" [(ngModel)]="newStock.itemId">
                      <option [ngValue]="undefined">&mdash; pick &mdash;</option>
                      @for (it of items; track it.id) { <option [ngValue]="it.id">{{ it.name }} ({{ it.slug }})</option> }
                    </select>
                  </label>
                  @if (submitted.stock && !newStock.itemId) {
                    <p class="field-error" role="alert">pick an item to stock.</p>
                  }
                  <div class="grid-2">
                    <label>Buy price
                      <input type="number" name="newStockBuy" [(ngModel)]="newStock.buyPrice" min="1" />
                    </label>
                    <label>Sell price
                      <input type="number" name="newStockSell" [(ngModel)]="newStock.sellPrice" min="0" />
                    </label>
                    <label>Qty (-1 = &infin;)
                      <input type="number" name="newStockQty" [(ngModel)]="newStock.stockQty" min="-1" />
                    </label>
                  </div>
                  @if (submitted.stock && stockPriceError()) {
                    <p class="field-error" role="alert">{{ stockPriceError() }}</p>
                  }
                  <button type="button" class="primary-btn" (click)="createStock()"
                          [disabled]="!newStock.itemId">Add stock</button>
                </fieldset>
              } @else {
                <p class="hint">{{ selectedBuilding.kind }} buildings don't stock items.</p>
              }
              <button type="button" class="danger-btn" (click)="deleteBuilding(selectedBuilding)">Delete building</button>
              <p class="hint">deleting removes the building and all of its interior floor rooms.</p>
            }
          </section>
        }
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
    .theme-chip { display: inline-block; margin-left: 0.4rem; padding: 0 0.4rem; border-radius: 999px;
      background: #d3e2ff; color: #20448c; font-size: 0.7rem; border: 1px solid #8aa8d8; vertical-align: middle; }
    .floor-badge { display: inline-block; margin-left: 0.4rem; padding: 0 0.4rem; border-radius: 3px;
      background: #e8e0c8; color: #6b5a1f; font-size: 0.7rem; border: 1px solid #cbb87a; }
    .room-static { color: #20448c; }
    .grid-2 { display: grid; grid-template-columns: 1fr 1fr; gap: 0 0.6rem; }
    .grid-2 label { margin: 0.3rem 0 0.4rem; }
    .place-grid { position: relative; width: 100%; max-width: 22rem; aspect-ratio: 1 / 1;
      margin: 0.4rem 0; border: 1px solid #8aa8d8; border-radius: 4px; background: #f5f9ff; }
    .cell-layer { position: absolute; inset: 0; display: grid; }
    .place-cell { border: 1px solid #d6e2f5; background: transparent; padding: 0; cursor: pointer; }
    .place-cell:hover { background: #dfeaff; }
    .bldg-block { position: absolute; z-index: 2; border: 1px solid rgba(0,0,0,0.35); border-radius: 3px;
      color: #fff; font-size: 0.6rem; line-height: 1.05; overflow: hidden; padding: 1px 2px; cursor: pointer;
      text-align: left; box-shadow: 0 1px 2px rgba(0,0,0,0.25); }
    .bldg-block.selected { outline: 2px solid #15294a; outline-offset: -1px; }
    .danger-btn { padding: 0.4rem 0.9rem; border-radius: 4px; border: 1px solid #8b1f1f;
      background: #f9d5d5; color: #8b1f1f; cursor: pointer; margin-top: 0.4rem; }
    .avatar-composer { display: flex; gap: 0.8rem; align-items: flex-start; }
    .avatar-preview-wrap { position: relative; flex: none; width: 9rem; }
    .avatar-controls { flex: 1; min-width: 0; }
    .avatar-preview { display: block; width: 100%; aspect-ratio: 2 / 3; object-fit: contain;
      background: #f5f9ff; border: 1px solid #8aa8d8; border-radius: 4px; margin: 0; }
    .avatar-preview-empty { display: flex; align-items: center; justify-content: center;
      font-size: 0.75rem; color: #20448c; }
    .preview-chip { position: absolute; top: 0.25rem; right: 0.25rem; padding: 0 0.3rem;
      border-radius: 3px; background: rgba(255,255,255,0.88); color: #20448c; font-size: 0.65rem; }
    .control-group { margin: 0.3rem 0 0.6rem; }
    .segmented { display: flex; flex-wrap: wrap; gap: 0.2rem 0.8rem; align-items: center; }
    .radio-row { display: inline-flex; align-items: center; gap: 0.3rem; margin: 0.2rem 0; }
    .radio-row input { width: auto; }
    .color-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(6rem, 1fr)); gap: 0 0.5rem; }
    .color-grid input { height: 1.8rem; padding: 0.1rem; }
    .scale-readout { font-weight: 600; margin-left: 0.3rem; }
    .btn-row { display: flex; flex-wrap: wrap; gap: 0.4rem; margin-top: 0.4rem; }
    .npc-roster .npc-row { display: flex; gap: 0.5rem; align-items: flex-start; margin: 0.35rem 0; }
    .portrait-thumb { width: 3rem; height: 3rem; object-fit: cover; border-radius: 4px;
      border: 1px solid #aabddf; background: #fff; flex: none; }
    .portrait-empty { display: inline-flex; align-items: center; justify-content: center;
      color: #8aa8d8; font-size: 1.4rem; }
    .npc-meta { flex: 1; }
    .npc-meta p.hint { margin: 0.1rem 0 0.3rem; }
  `],
})
export class RpgMakerRemoteComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  // NOTE: destroy$ is the BUILD-POLL cancel signal, not a component-lifetime
  // one — pollBuild() nexts it whenever a build reaches DONE/FAILED. Anything
  // that must outlive a build (the preview pipeline) uses destroyed$ instead.
  private destroy$ = new Subject<void>();
  private destroyed$ = new Subject<void>();
  readonly directions = DIRECTIONS;
  readonly assetKinds = ASSET_KINDS;
  readonly generateKinds = GENERATE_KINDS;
  readonly themes = THEMES;
  readonly renderStyles = RENDER_STYLES;
  readonly bodyShapes = BODY_SHAPES;
  readonly headShapes = HEAD_SHAPES;
  readonly hairStyles = HAIR_STYLES;
  readonly outfitTypes = OUTFIT_TYPES;
  readonly hatTypes = HAT_TYPES;
  readonly heldMain = HELD_MAIN;
  readonly heldOff = HELD_OFF;
  readonly backItems = BACK_ITEMS;
  readonly faceItems = FACE_ITEMS;
  readonly expressions = EXPRESSIONS;
  readonly proportions = PROPORTIONS;
  readonly colorSlots = COLOR_SLOTS;
  readonly buildingKinds = BUILDING_KINDS;
  readonly footprintSizes = FOOTPRINT_SIZES;
  readonly floorChoices = FLOOR_CHOICES;
  readonly basementChoices = BASEMENT_CHOICES;
  readonly grid = GRID;
  readonly cellPct = 100 / GRID;
  readonly gridCells: { x: number; y: number }[] = this.buildGridCells();

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
  newArea: Partial<RpgArea> = { theme: 'plain' };
  newRoom: Partial<RpgRoom> = {};
  newExit: Partial<RpgExit> = {};
  newItem: Partial<RpgItem> = {};
  newNpc: Partial<RpgNpc> = {};
  newAsset: Partial<RpgAsset> = {};
  genAsset: { slug?: string; kind?: string; theme?: string } = {};
  generating = false;
  submitted = { project: false, area: false, room: false, exit: false, item: false, npc: false, asset: false, generate: false, building: false, stock: false };
  buildTargetSlug = '';
  buildInFlight = false;
  currentBuild: RpgBuildJob | null = null;

  // ── Characters pane state (hero character + NPC portrait roster) ───────
  avatarConfig: RpgAvatarConfig = this.defaultAvatarConfig();
  /**
   * Data URI of the last SVG the SERVER rendered.
   *
   * <p>An &lt;img src="data:image/svg+xml,…"&gt; rather than [innerHTML]:
   * image context can neither run script nor pull subresources, so no
   * DomSanitizer bypass is needed for markup the service produced, and the
   * host CSP already allows `img-src 'self' data: blob:`. It also makes
   * "keep the old frame while the new one renders" a one-line rule — the
   * src simply isn't reassigned until the next SVG arrives.
   */
  previewSrc = '';
  previewPending = false;
  previewError = '';
  /** Render-time only (decision 028) — never part of the saved geometry. */
  previewExpression: string = EXPRESSIONS[0];
  importDoc = '';
  importError = '';
  private previewRequests$ = new Subject<void>();

  // ── Buildings pane state (town-themed areas) ────────────────────────────
  buildings: RpgBuilding[] = [];
  selectedHostRoom: RpgRoom | null = null;
  selectedBuilding: RpgBuilding | null = null;
  stock: RpgShopStock[] = [];
  newBuilding: Partial<RpgBuilding> = this.freshBuilding();
  newStock: Partial<RpgShopStock> = { stockQty: -1 };
  buildingFormError = '';
  private pollErrors = 0;

  ngOnInit(): void { this.wirePreview(); this.refreshProjects(); }

  ngOnDestroy(): void { this.destroyed$.next(); this.destroyed$.complete(); }

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
    this.buildings = []; this.selectedHostRoom = null; this.selectedBuilding = null; this.stock = [];
    this.avatarConfig = this.defaultAvatarConfig();
    // Drop the outgoing project's hero rather than leave its face on screen
    // while the new project's config loads.
    this.previewSrc = ''; this.previewError = ''; this.previewPending = false;
    this.importDoc = ''; this.importError = '';
    this.loadAvatarConfig();
    this.http.get<RpgBuilding[]>(`/api/rpg/projects/${p.id}/buildings`).subscribe({
      next: b => this.buildings = b,
      error: e => this.errorBanner = this.fmt(e, 'load buildings'),
    });
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
    // The Buildings pane is per-area (and per host room) — reset its
    // selection so a stale host room / building doesn't bleed across areas.
    this.selectedHostRoom = null; this.selectedBuilding = null; this.stock = [];
    this.newBuilding = this.freshBuilding(); this.submitted.building = false; this.buildingFormError = '';
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
      next: a => { this.areas = [...this.areas, a]; this.newArea = { theme: 'plain' }; this.submitted.area = false; this.successBanner = 'Area added.'; },
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

  // ── Characters pane (hero character composer + NPC portrait roster) ─────

  /** Column defaults of rpg_avatar_config — what the server synthesises
   *  for a project that has no saved row yet. Derived from PROPORTIONS /
   *  COLOR_SLOTS so a new field cannot be forgotten here. */
  defaultAvatarConfig(): RpgAvatarConfig {
    const c: Partial<RpgAvatarConfig> = {
      id: null, renderStyle: 'smooth', bodyShape: 'slim', headShape: 'round',
      hairStyle: 'short', outfitType: 'robe', hatType: 'none', heldMain: 'none',
      heldOff: 'none', backItem: 'none', faceItem: 'none', skinTextureAssetId: null,
    };
    for (const p of PROPORTIONS) c[p.wire] = p.dflt;
    for (const s of COLOR_SLOTS) c[s.wire] = s.dflt;
    return c as RpgAvatarConfig;
  }

  /**
   * One debounced render pipeline for the whole pane.
   *
   * <p>switchMap drops a superseded render, so a slider drag costs ONE
   * request rather than one per pixel, and the last good data URI stays on
   * screen until the next SVG lands — the preview never flickers to blank.
   *
   * <p>Deliberately NOT on destroy$: that subject doubles as the build-poll
   * cancel signal, so sharing it would silently kill the preview for the
   * rest of the session the first time a build finished.
   */
  private wirePreview(): void {
    this.previewRequests$.pipe(
      debounceTime(PREVIEW_DEBOUNCE_MS),
      switchMap(() => this.renderPreview()),
      takeUntil(this.destroyed$),
    ).subscribe(svg => {
      this.previewPending = false;
      this.previewError = '';
      this.previewSrc = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;
    });
  }

  /**
   * Render the CURRENT (unsaved) config through the same renderer that
   * produces the shipped art — decision 028's parity mechanism. Nothing is
   * persisted, and no geometry is computed client-side: a second drawing
   * routine here is exactly the drift the decision removed.
   */
  private renderPreview(): Observable<string> {
    const params = new HttpParams()
      .set('expression', this.previewExpression)
      .set('w', PREVIEW_W)
      .set('h', PREVIEW_H);
    return this.http.post('/api/rpg/avatar-preview.svg', this.avatarPayload(),
                          { params, responseType: 'text' }).pipe(
      catchError(e => {
        this.previewPending = false;
        // Inline next to the controls that caused it — the server's own
        // whitelist message ("unknown hairStyle 'x'") IS the useful part.
        this.previewError = this.fmt(e, 'render preview');
        return EMPTY;   // last good frame stays on screen
      }));
  }

  /** Queue a debounced re-render. Every control in the pane calls this. */
  avatarChanged(): void {
    this.previewPending = true;
    this.previewRequests$.next();
  }

  /**
   * Exactly the whitelisted wire fields. Shared by the PUT and the preview
   * POST so the two can never describe different characters — the server
   * stamps id/projectId itself and 400s anything off-whitelist.
   */
  private avatarPayload(): Record<string, unknown> {
    const c = this.avatarConfig;
    const body: Record<string, unknown> = {
      renderStyle: c.renderStyle, bodyShape: c.bodyShape, headShape: c.headShape,
      hairStyle: c.hairStyle, outfitType: c.outfitType, hatType: c.hatType,
      heldMain: c.heldMain, heldOff: c.heldOff, backItem: c.backItem, faceItem: c.faceItem,
      skinTextureAssetId: c.skinTextureAssetId ?? null,
    };
    for (const p of PROPORTIONS) body[p.wire] = this.clampProportion(p, c[p.wire]);
    for (const s of COLOR_SLOTS) body[s.wire] = c[s.wire];
    return body;
  }

  /** GET always answers (saved row, or a synthesised default with id null),
   *  so the composer is populated on every project select. */
  loadAvatarConfig(): void {
    if (!this.selectedProject) return;
    this.http.get<RpgAvatarConfig>(`/api/rpg/projects/${this.selectedProject.id}/avatar-config`).subscribe({
      next: c => this.adoptConfig(c),
      error: e => this.errorBanner = this.fmt(e, 'load avatar config'),
    });
  }

  /** Fold a server row over the defaults so a column the service hasn't
   *  learned yet still renders, then re-preview. */
  private adoptConfig(c: Partial<RpgAvatarConfig>): void {
    this.avatarConfig = { ...this.defaultAvatarConfig(), ...c };
    this.previewError = '';
    this.avatarChanged();
  }

  /** PUT upsert — the whole whitelisted payload in one shot. */
  saveAvatarConfig(): void {
    if (!this.selectedProject) return;
    this.http.put<RpgAvatarConfig>(`/api/rpg/projects/${this.selectedProject.id}/avatar-config`,
                                   this.avatarPayload()).subscribe({
      next: c => { this.adoptConfig(c); this.successBanner = 'Hero character saved.'; },
      error: e => this.errorBanner = this.fmt(e, 'save avatar config'),
    });
  }

  /**
   * Back to the column defaults. id / projectId survive because row identity
   * is the server's to stamp, and so does skinTextureAssetId: this pane has
   * no texture control, so clearing one the author cannot see would be
   * silent data loss rather than a reset.
   */
  resetAvatarConfig(): void {
    this.avatarConfig = {
      ...this.defaultAvatarConfig(),
      id: this.avatarConfig.id,
      projectId: this.avatarConfig.projectId,
      skinTextureAssetId: this.avatarConfig.skinTextureAssetId ?? null,
    };
    this.previewError = '';
    this.avatarChanged();
    this.successBanner = 'Character reset to defaults — save to keep it.';
  }

  /** Clamp to the humanoid bound; non-numeric → that bound's default. The
   *  server REJECTS an out-of-range value rather than clamping it, so a
   *  rogue assignment must never leave here. */
  clampProportion(p: Proportion, v: unknown): number {
    const n = Number(v);
    if (!Number.isFinite(n)) return p.dflt;
    return Math.min(p.max, Math.max(p.min, n));
  }

  setProportion(p: Proportion, v: unknown): void {
    this.avatarConfig[p.wire] = this.clampProportion(p, v);
    this.avatarChanged();
  }

  proportionLabel(p: Proportion): string {
    return `${this.clampProportion(p, this.avatarConfig[p.wire]).toFixed(2)}×`;
  }

  setColor(s: ColorSlotDef, v: string): void {
    this.avatarConfig[s.wire] = v;
    this.avatarChanged();
  }

  setRenderStyle(v: string): void {
    this.avatarConfig.renderStyle = v;
    this.avatarChanged();
  }

  /** Expression changes the PREVIEW only — it is a render-time face pose,
   *  so it is absent from the saved payload entirely. */
  setExpression(v: string): void {
    this.previewExpression = v;
    this.avatarChanged();
  }

  renderStyleLabel(s: string): string {
    return s === 'lowpoly' ? 'lowpoly — retro N64 look' : 'smooth';
  }

  /**
   * Export the portable Character Document — decision 028's interchange
   * format, readable by another generator, game or service.
   */
  downloadCharacter(): void {
    if (!this.selectedProject) return;
    this.importError = '';
    this.http.get(`/api/rpg/projects/${this.selectedProject.id}/avatar.json`,
                  { responseType: 'blob' }).subscribe({
      next: (blob: Blob) => {
        this.saveBlob(blob, `${this.selectedProject!.slug}-character.json`);
        this.successBanner = 'Character document downloaded.';
      },
      error: e => this.errorBanner = this.fmt(e, 'download character'),
    });
  }

  /** Load a .json file into the paste box so both import routes go through
   *  the same parse + POST. */
  onImportFile(ev: Event): void {
    const file = (ev.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.importError = '';
    file.text().then(
      t => this.importDoc = t,
      () => this.importError = 'could not read that file.',
    );
  }

  /**
   * POST a portable Character Document. Parsed locally first so a typo is an
   * immediate inline message instead of a round-trip; anything that parses
   * is still fully validated server-side — an import is not a way past the
   * whitelist.
   */
  importCharacter(): void {
    if (!this.selectedProject) return;
    this.importError = '';
    let doc: unknown;
    try {
      doc = JSON.parse(this.importDoc);
    } catch {
      this.importError = 'that is not valid JSON.';
      return;
    }
    this.http.post<RpgAvatarConfig>(
      `/api/rpg/projects/${this.selectedProject.id}/avatar.json`, doc).subscribe({
      next: c => {
        this.adoptConfig(c);
        this.importDoc = '';
        this.successBanner = 'Character document imported.';
      },
      error: e => this.importError = this.fmt(e, 'import character'),
    });
  }

  dialogueSnippet(n: RpgNpc): string {
    const t = (n.dialogueText || '').trim();
    if (!t) return '(no dialogue)';
    return t.length > 80 ? `${t.slice(0, 77)}…` : t;
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

  /**
   * Generate house-style placeholder art server-side (POST .../assets/generate),
   * then fold the created asset into the gallery exactly like an upload — no file
   * picker needed. Reuses the same asset-list state + preview loader.
   */
  generateAsset(): void {
    if (!this.selectedProject) return;
    this.submitted.generate = true;
    if (!this.slugOk(this.genAsset.slug) || !this.genAsset.kind?.trim()) return;
    this.generating = true;
    const body = { slug: this.genAsset.slug!, kind: this.genAsset.kind!, theme: this.genAsset.theme?.trim() || null };
    this.http.post<RpgAsset>(`/api/rpg/projects/${this.selectedProject.id}/assets/generate`, body).subscribe({
      next: a => {
        this.assets = [...this.assets, a];
        this.loadAssetPreview(a);
        this.genAsset = {}; this.submitted.generate = false; this.generating = false;
        this.successBanner = `Generated ${a.kind} art "${a.slug}".`;
      },
      error: e => { this.generating = false; this.errorBanner = this.fmt(e, 'generate art'); },
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
        this.saveBlob(blob, `${this.selectedProject!.slug}-godot.zip`);
        this.successBanner = 'Godot source downloaded.';
      },
      error: e => this.errorBanner = this.fmt(e, 'download godot zip'),
    });
  }

  /** Hand a fetched blob to the browser's downloader. */
  private saveBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
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

  /**
   * Poll /api/rpg/builds/{id} every 2 s. RESOLUTION 13: a REAL
   * import+export starves the builder JVM for minutes, so a single
   * timed-out / 5xx GET must NOT permanently freeze the progress bar (the
   * previous code stopped polling on the first error). Tolerate up to
   * {@link MAX_POLL_ERRORS} CONSECUTIVE failures — swallow each with an
   * EMPTY (keeps the 2 s cadence, subscriber not invoked so the counter
   * survives) and reset the counter on every success.
   */
  private pollBuild(id: string): void {
    this.pollErrors = 0;
    timer(0, 2000).pipe(
      takeUntil(this.destroy$),
      switchMap(() => this.http.get<RpgBuildJob>(`/api/rpg/builds/${id}`).pipe(
        catchError(e => {
          if (++this.pollErrors >= MAX_POLL_ERRORS) {
            this.errorBanner = this.fmt(e, 'poll build');
            this.buildInFlight = false;
            this.destroy$.next();
          }
          return EMPTY; // transient: skip this tick, keep the timer running
        }))),
    ).subscribe(j => {
      this.pollErrors = 0;
      this.currentBuild = j;
      if (j.status === 'DONE' || j.status === 'FAILED') {
        this.buildInFlight = false;
        this.destroy$.next();
        if (j.status === 'DONE') this.successBanner = 'Bundle uploaded successfully.';
      }
    });
  }

  // ── Buildings + shop stock (town-themed areas) ──────────────────────────

  private buildGridCells(): { x: number; y: number }[] {
    const cells: { x: number; y: number }[] = [];
    for (let y = 0; y < GRID; y++) for (let x = 0; x < GRID; x++) cells.push({ x, y });
    return cells;
  }

  private freshBuilding(): Partial<RpgBuilding> {
    return { kind: 'item_shop', floors: 1, basements: 0, width: 1, depth: 1 };
  }

  /** Outdoor rooms of the selected town area that can host a building
   *  (interior floor rooms — buildingId set — cannot host another). */
  hostRoomChoices(): RpgRoom[] {
    return this.roomsInSelectedArea().filter(r => !r.buildingId);
  }

  buildingsOnHostRoom(): RpgBuilding[] {
    return this.selectedHostRoom
      ? this.buildings.filter(b => b.hostRoomId === this.selectedHostRoom!.id)
      : [];
  }

  isShopKind(kind: string): boolean { return SHOP_KINDS.has(kind); }

  kindColor(kind: string): string { return KIND_COLORS[kind] ?? '#5a5a6a'; }

  itemName(id: string): string { return this.items.find(it => it.id === id)?.name ?? id; }

  selectHostRoom(r: RpgRoom): void {
    this.selectedHostRoom = r;
    this.selectedBuilding = null; this.stock = [];
    this.buildingFormError = ''; this.submitted.building = false;
  }

  selectBuilding(b: RpgBuilding): void {
    this.selectedBuilding = b;
    this.stock = []; this.submitted.stock = false; this.newStock = { stockQty: -1 };
    if (this.selectedProject && this.isShopKind(b.kind)) {
      this.http.get<RpgShopStock[]>(
        `/api/rpg/projects/${this.selectedProject.id}/buildings/${b.id}/stock`).subscribe({
        next: s => this.stock = s,
        error: e => this.errorBanner = this.fmt(e, 'load stock'),
      });
    }
  }

  /** Click an empty grid cell to place the pending building there. The
   *  form must already be valid; the cell provides gridX/gridY. */
  placeAt(x: number, y: number): void {
    if (!this.selectedProject || !this.selectedHostRoom) return;
    this.submitted.building = true;
    this.buildingFormError = '';
    if (!this.slugOk(this.newBuilding.slug) || !this.newBuilding.name?.trim()) {
      this.buildingFormError = 'give the building a valid slug and name before placing it.';
      return;
    }
    const w = this.newBuilding.width ?? 1, d = this.newBuilding.depth ?? 1;
    if (x + w > GRID || y + d > GRID) {
      this.buildingFormError = 'footprint would run off the grid — pick a cell nearer the top-left.';
      return;
    }
    if (this.buildingsOnHostRoom().some(b => this.overlaps(x, y, w, d, b))) {
      this.buildingFormError = 'that footprint overlaps an existing building — pick a clear cell.';
      return;
    }
    const body: Partial<RpgBuilding> = {
      ...this.newBuilding, hostRoomId: this.selectedHostRoom.id, gridX: x, gridY: y,
    };
    this.http.post<RpgBuilding>(
      `/api/rpg/projects/${this.selectedProject.id}/buildings`, body).subscribe({
      next: b => {
        this.buildings = [...this.buildings, b];
        this.newBuilding = this.freshBuilding();
        this.submitted.building = false;
        this.successBanner = `Building "${b.name}" placed.`;
        this.reloadRooms();      // interior floor rooms were auto-created
        this.selectBuilding(b);
      },
      error: e => this.errorBanner = this.fmt(e, 'create building'),
    });
  }

  private overlaps(x: number, y: number, w: number, d: number, b: RpgBuilding): boolean {
    return x < b.gridX + b.width && b.gridX < x + w && y < b.gridY + b.depth && b.gridY < y + d;
  }

  deleteBuilding(b: RpgBuilding): void {
    if (!this.selectedProject) return;
    if (!confirm(`Delete "${b.name}"? Its interior floor rooms (and their exits) will be deleted too.`)) return;
    this.http.delete<void>(
      `/api/rpg/projects/${this.selectedProject.id}/buildings/${b.id}`).subscribe({
      next: () => {
        this.buildings = this.buildings.filter(x => x.id !== b.id);
        if (this.selectedBuilding?.id === b.id) { this.selectedBuilding = null; this.stock = []; }
        this.successBanner = `Building "${b.name}" deleted.`;
        this.reloadRooms();
      },
      error: e => this.errorBanner = this.fmt(e, 'delete building'),
    });
  }

  stockPriceError(): string {
    const buy = Number(this.newStock.buyPrice), sell = Number(this.newStock.sellPrice);
    if (!(buy > 0)) return 'buy price must be > 0.';
    if (!(sell >= 0)) return 'sell price must be >= 0.';
    if (sell > buy) return 'sell price must be <= buy price.';
    return '';
  }

  createStock(): void {
    if (!this.selectedProject || !this.selectedBuilding) return;
    this.submitted.stock = true;
    if (!this.newStock.itemId || this.stockPriceError()) return;
    const body: Partial<RpgShopStock> = {
      itemId: this.newStock.itemId,
      buyPrice: Number(this.newStock.buyPrice),
      sellPrice: Number(this.newStock.sellPrice),
      stockQty: this.newStock.stockQty == null ? -1 : Number(this.newStock.stockQty),
    };
    this.http.post<RpgShopStock>(
      `/api/rpg/projects/${this.selectedProject.id}/buildings/${this.selectedBuilding.id}/stock`, body).subscribe({
      next: s => {
        this.stock = [...this.stock, s];
        this.newStock = { stockQty: -1 }; this.submitted.stock = false;
        this.successBanner = 'Stock line added.';
      },
      error: e => this.errorBanner = this.fmt(e, 'add stock'),
    });
  }

  deleteStock(s: RpgShopStock): void {
    if (!this.selectedProject || !this.selectedBuilding) return;
    this.http.delete<void>(
      `/api/rpg/projects/${this.selectedProject.id}/buildings/${this.selectedBuilding.id}/stock/${s.id}`).subscribe({
      next: () => { this.stock = this.stock.filter(x => x.id !== s.id); this.successBanner = 'Stock line removed.'; },
      error: e => this.errorBanner = this.fmt(e, 'delete stock'),
    });
  }

  private reloadRooms(): void {
    if (!this.selectedProject) return;
    this.http.get<RpgRoom[]>(`/api/rpg/projects/${this.selectedProject.id}/rooms`).subscribe({
      next: r => this.rooms = r,
      error: e => this.errorBanner = this.fmt(e, 'load rooms'),
    });
  }

  private fmt(err: unknown, action: string): string {
    if (err instanceof HttpErrorResponse) {
      return `${action} failed (${err.status}): ${this.serverMessage(err)}`;
    }
    return `${action} failed: ${String(err)}`;
  }

  /**
   * The message the SERVER actually sent, whatever shape it arrived in.
   *
   * <p>The builder's {@code @ExceptionHandler} answers a whitelist rejection
   * with a text/plain body ("unknown hairStyle 'x'"), which lands as a raw
   * string on a text request and as {@code {error: SyntaxError, text: '…'}}
   * when Angular tried and failed to JSON.parse it. Neither shape carries
   * .message or .detail, so the previous lookup fell through to Angular's
   * generic "Http failure response for …" and the author never learned WHY
   * their character was rejected.
   */
  private serverMessage(err: HttpErrorResponse): string {
    const body: unknown = err.error;
    if (typeof body === 'string' && body.trim()) return body;
    if (body && typeof body === 'object') {
      const o = body as { message?: unknown; detail?: unknown; text?: unknown };
      for (const v of [o.message, o.detail, o.text]) {
        if (typeof v === 'string' && v.trim()) return v;
      }
    }
    return err.message;
  }
}
