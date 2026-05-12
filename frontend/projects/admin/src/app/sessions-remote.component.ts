import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

interface SessionMember {
  casdoorId: string;
  characterId: number | null;
  slotIndex: number | null;
  creator: boolean;
  joinedAt: string;
}
interface GameSession {
  id: string;
  name: string;
  gameSlug: string;
  creatorCasdoorId: string;
  maxPlayers: number;
  activePlayers: number;
  visibility?: string;
  createdAt: string;
  closedAt: string | null;
  pausedAt?: string | null;
  pausedByCasdoorId?: string | null;
  pauseReason?: string | null;
  members: SessionMember[];
}
interface ViewSample {
  sessionId: string;
  slotIndex: number;
  characterId: number;
  x: number; y: number; z: number;
  yaw: number; pitch: number; fov: number;
  occurredAt: string;
}

interface EventLogRow {
  ts: string;            // local-time HH:mm:ss.SSS
  channel: string;       // movement | world | character | view | scene | input | audio | wasm-console
  summary: string;       // one-line condensed
}

interface SeriesPoint {
  t: number;             // ms since stream-start
  count: number;         // events in that bucket
}

const EVENT_CHANNELS = [
  'movement', 'world', 'character', 'view',
  'scene', 'input', 'audio', 'wasm-console',
] as const;
type EventChannel = (typeof EVENT_CHANNELS)[number];

/**
 * Federated admin SessionsShell — observe one multiplayer session in
 * real time. Loaded by `/admin/sessions` via the admin remote.
 *
 * <p>Three panels:
 *
 * <ol>
 *   <li>Searchable session list (left). Backed by
 *       `GET /api/admin/multiplayer-sessions` so the admin sees every
 *       open session regardless of visibility (the unfiltered oversight
 *       endpoint added in this PR; the public `/api/sessions` route
 *       applies the friend-graph filter even for admins, which hid
 *       friend / private sessions from the dashboard).</li>
 *
 *   <li>Selected session detail (right top) — members + activePlayers
 *       headline plus the per-slot view sample table.</li>
 *
 *   <li>Live event terminal + per-channel rate graph (right bottom) —
 *       streams every SSE channel from
 *       `/api/admin/sessions/{id}/stream` and renders the latest 200
 *       lines as a scrolling log plus a 60-second rolling histogram
 *       per channel.</li>
 * </ol>
 *
 * <p>Auth: ROLE_ADMIN (BFF `@PreAuthorize` on the stream endpoint).
 * The host's authGuard already requires authenticated; non-admin
 * callers get 403 from the SSE stream and the view panel surfaces an
 * empty state.
 */
@Component({
  selector: 'app-sessions-remote',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="sessions-page">
      <header class="sessions-header">
        <h1>&#9899; Live sessions</h1>
        <button class="btn" (click)="refreshList()">&#8635; Refresh list</button>
      </header>

      <div class="sessions-layout">
        <aside class="sessions-list">
          <input class="search-input"
                 type="search"
                 placeholder="Search by name, game, creator…"
                 [(ngModel)]="search"
                 (ngModelChange)="onSearchChange($event)"
                 aria-label="Search sessions" />

          <p class="muted small">
            {{ filteredCount() }} of {{ sessions().length }} open
          </p>

          @if (sessions().length === 0) {
            <p class="muted">No open sessions.</p>
          } @else if (filteredCount() === 0) {
            <p class="muted">No matches for "{{ search }}".</p>
          } @else {
            <ul>
              @for (s of filtered(); track s.id) {
                <li>
                  <button type="button"
                          [class.active]="selected()?.id === s.id"
                          (click)="select(s)">
                    <strong>{{ s.name }}</strong>
                    <span class="row-meta">
                      {{ s.gameSlug }} · {{ s.activePlayers }} / {{ s.maxPlayers }}
                    </span>
                    <span class="row-meta vis-tag" [attr.data-vis]="s.visibility || 'public'">
                      {{ s.visibility || 'public' }}
                    </span>
                    @if (s.pausedAt) {
                      <span class="row-meta paused-pill"
                            [attr.title]="'Paused: ' + (s.pauseReason || 'manual')">
                        ⏸ paused
                      </span>
                    }
                  </button>
                </li>
              }
            </ul>
          }
        </aside>

        <section class="session-detail">
          @if (selected(); as sel) {
            <h2>
              {{ sel.name }} <span class="muted small">{{ sel.gameSlug }}</span>
              @if (sel.pausedAt) {
                <span class="paused-pill"
                      [attr.title]="'Paused at ' + sel.pausedAt + ' — ' + (sel.pauseReason || 'manual')">
                  ⏸ paused
                </span>
              }
            </h2>
            <p class="meta">
              by {{ sel.creatorCasdoorId }} ·
              created {{ sel.createdAt | date:'short' }} ·
              {{ sel.activePlayers }} / {{ sel.maxPlayers }} active
              @if (sel.pausedAt) {
                · paused by {{ sel.pausedByCasdoorId || '—' }}
                ({{ sel.pauseReason || 'manual' }})
              }
            </p>

            <h3>Members</h3>
            <ul class="member-list">
              @for (m of sel.members; track m.casdoorId) {
                <li>
                  <span class="slot-pill">slot {{ m.slotIndex ?? '–' }}</span>
                  <span>{{ m.casdoorId }}</span>
                  <span class="muted">char #{{ m.characterId ?? '–' }}</span>
                  @if (m.creator) { <em>(creator)</em> }
                </li>
              }
            </ul>

            <h3>Live event terminal</h3>
            <div class="filter-row">
              <span class="muted small">Channels:</span>
              @for (ch of channelList; track ch) {
                <label class="channel-toggle">
                  <input type="checkbox"
                         [checked]="visibleChannels().has(ch)"
                         (change)="toggleChannel(ch)" />
                  <span class="ch-name ch-{{ ch }}">{{ ch }}</span>
                </label>
              }
              <button class="btn-sm" type="button" (click)="clearEvents()">Clear</button>
            </div>
            @if (events().length === 0) {
              <pre class="event-terminal empty">No events yet — waiting on the stream…</pre>
            } @else {
              <pre class="event-terminal" #termPre>@for (e of events(); track $index) {<span class="line ch-{{ e.channel }}"><span class="ts">{{ e.ts }}</span><span class="ch">{{ e.channel }}</span><span class="sum">{{ e.summary }}</span></span>}</pre>
            }

            <h3>Channel rate (last 60 s)</h3>
            <div class="charts-grid">
              @for (ch of channelList; track ch) {
                @if (visibleChannels().has(ch)) {
                  <figure class="chart">
                    <figcaption>
                      <span class="ch-name ch-{{ ch }}">{{ ch }}</span>
                      <span class="muted small">{{ channelTotal(ch) }} total</span>
                    </figcaption>
                    <svg viewBox="0 0 120 32" preserveAspectRatio="none" class="chart-svg">
                      <polyline [attr.points]="seriesPoints(ch)"
                                fill="none" stroke="currentColor"
                                stroke-width="1" />
                    </svg>
                  </figure>
                }
              }
            </div>

            <h3>Per-player view</h3>
            <p class="muted small">
              Live camera + orientation samples (~10 Hz) from each
              player's HostBridge.report_view.
            </p>
            @if (viewSamples().size === 0) {
              <p class="muted">No view samples yet — players haven't moved.</p>
            } @else {
              <table class="view-table">
                <thead>
                  <tr>
                    <th>Slot</th><th>Character</th>
                    <th>Pos</th><th>Look</th><th>FOV</th><th>At</th>
                  </tr>
                </thead>
                <tbody>
                  @for (entry of viewSampleArray(); track entry.slotIndex) {
                    <tr>
                      <td><span class="slot-pill">{{ entry.slotIndex }}</span></td>
                      <td>#{{ entry.characterId }}</td>
                      <td>({{ entry.x | number:'1.0-1' }},
                          {{ entry.y | number:'1.0-1' }},
                          {{ entry.z | number:'1.0-1' }})</td>
                      <td>yaw {{ entry.yaw | number:'1.0-2' }} ·
                          pitch {{ entry.pitch | number:'1.0-2' }}</td>
                      <td>{{ entry.fov | number:'1.0-0' }}°</td>
                      <td>{{ entry.occurredAt | date:'mediumTime' }}</td>
                    </tr>
                  }
                </tbody>
              </table>
            }
          } @else {
            <p class="muted">Select a session on the left to start observing.</p>
          }
        </section>
      </div>
    </div>
  `,
  styles: [`
    .sessions-page { padding: 1.5rem; color: #e8e8f0; }
    .sessions-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    h1 { margin: 0; font-size: 1.4rem; }
    h2 { margin: 0 0 .25rem; }
    h3 { margin: 1.1rem 0 .35rem; font-size: .95rem; color: #b388ff; }
    .sessions-layout { display: grid; grid-template-columns: 300px 1fr; gap: 1rem; }
    .search-input { width: 100%; padding: .45rem .6rem; border: 1px solid #2a2a40; border-radius: 4px; background: #0f0f1c; color: #e8e8f0; margin-bottom: .6rem; }
    .search-input:focus { outline: 2px solid #7c4dff; }
    .sessions-list ul { list-style: none; padding: 0; margin: 0; }
    .sessions-list li button { width: 100%; text-align: left; padding: .55rem .7rem; background: #12121e; border: 1px solid #1e1e32; border-radius: 4px; margin-bottom: .35rem; cursor: pointer; color: #e8e8f0; display: flex; flex-direction: column; gap: .15rem; }
    .sessions-list li button.active { border-color: #7c4dff; background: rgba(124,77,255,0.1); }
    .row-meta { font-size: .78rem; color: #a8acc4; }
    .vis-tag { font-style: italic; }
    .vis-tag[data-vis='public']  { color: #4caf50; }
    .vis-tag[data-vis='friends'] { color: #b388ff; }
    .vis-tag[data-vis='private'] { color: #ff8a65; }
    .paused-pill { color: #ffd180; background: rgba(255,209,128,0.12); border-radius: 999px; padding: 1px 8px; font-size: .76rem; margin-left: .35rem; }
    .session-detail { background: #12121e; border: 1px solid #1e1e32; border-radius: 6px; padding: 1rem; min-height: 600px; }
    .meta { color: #a8acc4; font-size: .88rem; margin: .15rem 0 .25rem; }
    .member-list { list-style: none; padding: 0; }
    .member-list li { padding: .25rem 0; display: flex; align-items: center; gap: .5rem; font-size: .9rem; }
    .slot-pill { display: inline-block; background: #7c4dff; color: #fff; border-radius: 999px; padding: .05rem .55rem; font-size: .78rem; min-width: 2.4em; text-align: center; }

    .filter-row { display: flex; flex-wrap: wrap; align-items: center; gap: .6rem; padding: .35rem 0; }
    .channel-toggle { display: inline-flex; align-items: center; gap: .25rem; cursor: pointer; font-size: .8rem; }
    .channel-toggle input { margin: 0; }
    .ch-name { font-family: ui-monospace, monospace; padding: 1px 6px; border-radius: 3px; font-size: .76rem; }
    .ch-movement     { background: rgba(76,175,80,0.18);  color: #a5d6a7; }
    .ch-world        { background: rgba(33,150,243,0.18); color: #90caf9; }
    .ch-character    { background: rgba(255,193,7,0.18);  color: #ffe082; }
    .ch-view         { background: rgba(124,77,255,0.18); color: #b388ff; }
    .ch-scene        { background: rgba(0,188,212,0.18);  color: #80deea; }
    .ch-input        { background: rgba(255,138,101,0.18); color: #ff8a65; }
    .ch-audio        { background: rgba(233,30,99,0.18);  color: #f48fb1; }
    .ch-wasm-console { background: rgba(244,67,54,0.18);  color: #ef9a9a; }

    .btn, .btn-sm { background: #1a1a2e; border: 1px solid #2a2a40; color: #e8e8f0; border-radius: 4px; padding: .35rem .75rem; cursor: pointer; font-size: .85rem; }
    .btn-sm { padding: .2rem .55rem; font-size: .75rem; }
    .btn:hover, .btn-sm:hover { border-color: #7c4dff; background: rgba(124,77,255,0.08); }

    .event-terminal { background: #050510; border: 1px solid #1e1e32; border-radius: 6px; color: #e8e8f0; font-family: ui-monospace, monospace; font-size: .75rem; line-height: 1.35; max-height: 220px; min-height: 100px; overflow: auto; padding: .5rem .6rem; margin: 0; white-space: pre; }
    .event-terminal.empty { color: #6a708e; font-style: italic; padding: .8rem .6rem; min-height: 60px; }
    .event-terminal .line { display: block; }
    .event-terminal .ts { color: #6a708e; margin-right: .5rem; }
    .event-terminal .ch { display: inline-block; min-width: 9ch; margin-right: .5rem; font-weight: 600; }
    .event-terminal .line.ch-movement .ch     { color: #a5d6a7; }
    .event-terminal .line.ch-world .ch        { color: #90caf9; }
    .event-terminal .line.ch-character .ch    { color: #ffe082; }
    .event-terminal .line.ch-view .ch         { color: #b388ff; }
    .event-terminal .line.ch-scene .ch        { color: #80deea; }
    .event-terminal .line.ch-input .ch        { color: #ff8a65; }
    .event-terminal .line.ch-audio .ch        { color: #f48fb1; }
    .event-terminal .line.ch-wasm-console .ch { color: #ef9a9a; }
    .event-terminal .sum { color: #d8dde6; }

    .charts-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: .5rem; }
    .chart { margin: 0; padding: .35rem .5rem; background: #0f0f1c; border: 1px solid #1e1e32; border-radius: 4px; }
    .chart figcaption { display: flex; justify-content: space-between; align-items: center; margin-bottom: .25rem; font-size: .75rem; }
    .chart-svg { width: 100%; height: 32px; display: block; }
    .chart .chart-svg { color: #b388ff; }

    .view-table { width: 100%; border-collapse: collapse; }
    .view-table th, .view-table td { padding: .3rem .5rem; border-bottom: 1px solid #1e1e32; text-align: left; font-size: .85rem; }
    .muted { color: #a8acc4; font-style: italic; }
    .small { font-size: .78rem; }
  `],
})
export class SessionsRemoteComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);

  readonly channelList = EVENT_CHANNELS;

  // List state
  readonly sessions = signal<GameSession[]>([]);
  search = '';
  readonly searchSignal = signal('');
  readonly filtered = computed<GameSession[]>(() => {
    const q = this.searchSignal().trim().toLowerCase();
    if (!q) return this.sessions();
    return this.sessions().filter(s =>
      s.name.toLowerCase().includes(q) ||
      s.gameSlug.toLowerCase().includes(q) ||
      s.creatorCasdoorId.toLowerCase().includes(q));
  });
  readonly filteredCount = computed(() => this.filtered().length);

  // Selected session + view samples (slot → latest)
  readonly selected = signal<GameSession | null>(null);
  readonly viewSamples = signal<Map<number, ViewSample>>(new Map());
  private es: EventSource | null = null;

  // Event terminal (capped FIFO)
  private static readonly EVENT_BUFFER = 200;
  readonly events = signal<EventLogRow[]>([]);
  readonly visibleChannels = signal<Set<EventChannel>>(new Set(EVENT_CHANNELS));

  // Per-channel 60-second rolling histogram (1-second buckets).
  private static readonly SERIES_BUCKETS = 60;
  private readonly series = new Map<EventChannel, number[]>();
  private streamStartedAt = 0;
  private seriesTimer: number | null = null;

  ngOnInit(): void { this.refreshList(); }
  ngOnDestroy(): void { this.closeStream(); }

  onSearchChange(v: string): void { this.searchSignal.set(v); }

  refreshList(): void {
    // Admin oversight endpoint — bypasses the friend-graph / visibility
    // filter so the admin sees every open session including friends-only
    // and private sessions, regardless of who created them.
    this.http.get<GameSession[]>('/api/admin/multiplayer-sessions').subscribe({
      next: list => this.sessions.set(list ?? []),
      error: () => this.sessions.set([]),
    });
  }

  toggleChannel(ch: EventChannel): void {
    const next = new Set(this.visibleChannels());
    if (next.has(ch)) next.delete(ch);
    else next.add(ch);
    this.visibleChannels.set(next);
  }

  clearEvents(): void { this.events.set([]); }

  select(s: GameSession): void {
    if (this.selected()?.id === s.id) return;
    this.selected.set(s);
    this.viewSamples.set(new Map());
    this.events.set([]);
    this.resetSeries();
    this.closeStream();

    this.es = new EventSource(`/api/admin/sessions/${s.id}/stream`);
    for (const ch of EVENT_CHANNELS) {
      this.es.addEventListener(ch, evt => this.onChannelEvent(ch, evt as MessageEvent));
    }
    this.es.onerror = () => console.warn('[SessionsShell] SSE error on session', s.id);
  }

  viewSampleArray(): ViewSample[] {
    return Array.from(this.viewSamples().values()).sort((a, b) => a.slotIndex - b.slotIndex);
  }

  channelTotal(ch: EventChannel): number {
    return (this.series.get(ch) ?? []).reduce((a, b) => a + b, 0);
  }

  /** Render the series as a polyline `x,y` list normalised to the 120x32 viewBox. */
  seriesPoints(ch: EventChannel): string {
    const buckets = this.series.get(ch) ?? [];
    if (buckets.length === 0) return '';
    const max = Math.max(1, ...buckets);
    const step = 120 / SessionsRemoteComponent.SERIES_BUCKETS;
    return buckets.map((c, i) => {
      const x = (i * step).toFixed(2);
      const y = (32 - (c / max) * 30 - 1).toFixed(2);
      return `${x},${y}`;
    }).join(' ');
  }

  // ── private ────────────────────────────────────────────────────────────

  private onChannelEvent(ch: EventChannel, e: MessageEvent): void {
    // Per-channel view sample maintenance.
    if (ch === 'view') {
      try {
        const sample: ViewSample = JSON.parse(e.data);
        const next = new Map(this.viewSamples());
        next.set(sample.slotIndex, sample);
        this.viewSamples.set(next);
      } catch { /* malformed — fall through to terminal */ }
    }

    // Event-terminal append (FIFO bounded).
    const row: EventLogRow = {
      ts:       this.formatTs(new Date()),
      channel:  ch,
      summary:  this.summariseData(ch, e.data),
    };
    const next = this.events().slice();
    next.push(row);
    if (next.length > SessionsRemoteComponent.EVENT_BUFFER) {
      next.splice(0, next.length - SessionsRemoteComponent.EVENT_BUFFER);
    }
    this.events.set(next);

    // Series bucket — increment the current second.
    const buckets = this.series.get(ch);
    if (buckets && buckets.length > 0) {
      buckets[buckets.length - 1]++;
    }
  }

  /** Condensed one-line view of an SSE payload, suitable for the terminal. */
  private summariseData(ch: EventChannel, raw: string): string {
    try {
      const parsed = JSON.parse(raw);
      if (ch === 'movement') {
        return `pos=(${this.fmtN(parsed.x)},${this.fmtN(parsed.y)},${this.fmtN(parsed.z)}) action=${parsed.action ?? '-'}`;
      }
      if (ch === 'view') {
        return `slot=${parsed.slotIndex} yaw=${this.fmtN(parsed.yaw)} pitch=${this.fmtN(parsed.pitch)}`;
      }
      if (ch === 'character') {
        return `hp=${parsed.health ?? '-'} mp=${parsed.mana ?? '-'} lvl=${parsed.level ?? '-'}`;
      }
      if (ch === 'world') {
        return `tick=${parsed.tick} chars=${(parsed.characters?.length) ?? 0}`;
      }
      if (ch === 'scene') {
        return `scene=${parsed.sceneName ?? parsed.sceneId} type=${parsed.eventType ?? '-'}`;
      }
      if (ch === 'wasm-console') {
        return `${parsed.level ?? 'log'} ${(parsed.message ?? '').slice(0, 120)}`;
      }
      if (ch === 'input') {
        return `${parsed.kind}/${parsed.type}`;
      }
      if (ch === 'audio') {
        return `cue=${parsed.cue ?? parsed.type ?? '-'}`;
      }
      return raw.slice(0, 120);
    } catch {
      return raw.slice(0, 120);
    }
  }

  private fmtN(v: unknown): string {
    if (typeof v !== 'number' || !isFinite(v)) return '-';
    return v.toFixed(1);
  }

  private formatTs(d: Date): string {
    const pad = (n: number, w = 2) => String(n).padStart(w, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
  }

  private resetSeries(): void {
    this.series.clear();
    for (const ch of EVENT_CHANNELS) {
      this.series.set(ch, new Array(SessionsRemoteComponent.SERIES_BUCKETS).fill(0));
    }
    this.streamStartedAt = Date.now();
    if (this.seriesTimer != null) {
      clearInterval(this.seriesTimer);
    }
    // Each second, push a new bucket and drop the oldest so the series
    // always represents the last 60 seconds of activity.
    this.seriesTimer = setInterval(() => {
      for (const ch of EVENT_CHANNELS) {
        const b = this.series.get(ch);
        if (!b) continue;
        b.push(0);
        if (b.length > SessionsRemoteComponent.SERIES_BUCKETS) b.shift();
      }
      // Trigger change detection — reassign sessions ref (a no-op set
      // is the cheapest way to nudge the computed graph signals).
      this.sessions.set(this.sessions());
    }, 1_000) as unknown as number;
  }

  private closeStream(): void {
    if (this.es) { this.es.close(); this.es = null; }
    if (this.seriesTimer != null) {
      clearInterval(this.seriesTimer);
      this.seriesTimer = null;
    }
  }
}
