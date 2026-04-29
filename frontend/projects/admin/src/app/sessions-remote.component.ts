import { Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';

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
  createdAt: string;
  closedAt: string | null;
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

/**
 * Federated admin SessionsShell — observe one multiplayer session in
 * real time. Loaded by `/admin/sessions` via the admin remote.
 *
 * Three panels:
 *   1. Session list (left) — every open session with player counts.
 *   2. Selected session detail (right top) — members + activePlayers
 *      headline.
 *   3. Per-player view stream (right bottom) — subscribes to the
 *      `/api/admin/sessions/{id}/stream` SSE endpoint and renders the
 *      latest camera/orientation sample per slot. The view channel is
 *      what lets an admin see "where each player is looking" without
 *      streaming video frames.
 *
 * Auth: ROLE_ADMIN (BFF @PreAuthorize on the stream endpoint). The
 * host's authGuard already requires authenticated; non-admin callers
 * get 403 from the SSE stream and the view panel surfaces an empty
 * state with the role hint.
 */
@Component({
  selector: 'app-sessions-remote',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="sessions-page">
      <header class="sessions-header">
        <h1>&#9899; Live sessions</h1>
        <button class="btn" (click)="refreshList()">&#8635; Refresh list</button>
      </header>

      <div class="sessions-layout">
        <aside class="sessions-list">
          @if (sessions$ | async; as list) {
            @if (list.length === 0) {
              <p class="muted">No open sessions.</p>
            } @else {
              <ul>
                @for (s of list; track s.id) {
                  <li>
                    <button type="button"
                            [class.active]="selected?.id === s.id"
                            (click)="select(s)">
                      <strong>{{ s.name }}</strong>
                      <span class="row-meta">
                        {{ s.gameSlug }} · {{ s.activePlayers }} / {{ s.maxPlayers }}
                      </span>
                    </button>
                  </li>
                }
              </ul>
            }
          }
        </aside>

        <section class="session-detail">
          @if (selected) {
            <h2>{{ selected.name }} <span class="muted small">{{ selected.gameSlug }}</span></h2>
            <p class="meta">
              by {{ selected.creatorCasdoorId }} ·
              created {{ selected.createdAt | date:'short' }} ·
              {{ selected.activePlayers }} / {{ selected.maxPlayers }} active
            </p>

            <h3>Members</h3>
            <ul class="member-list">
              @for (m of selected.members; track m.casdoorId) {
                <li>
                  <span class="slot-pill">slot {{ m.slotIndex ?? '–' }}</span>
                  <span>{{ m.casdoorId }}</span>
                  <span class="muted">char #{{ m.characterId ?? '–' }}</span>
                  @if (m.creator) { <em>(creator)</em> }
                </li>
              }
            </ul>

            <h3>Per-player view</h3>
            <p class="muted small">
              Live camera + orientation samples (~10 Hz) from each
              player's HostBridge.report_view. Disconnected slots fall
              off after their last sample ages out.
            </p>
            @if (viewSamples.size === 0) {
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
    .sessions-page { padding: 1.5rem; }
    .sessions-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; }
    h1 { margin: 0; font-size: 1.4rem; }
    .sessions-layout { display: grid; grid-template-columns: 280px 1fr; gap: 1rem; }
    .sessions-list ul { list-style: none; padding: 0; margin: 0; }
    .sessions-list li button { width: 100%; text-align: left; padding: 0.6rem 0.8rem; background: #fff; border: 1px solid #d8d8e0; border-radius: 4px; margin-bottom: 0.4rem; cursor: pointer; display: flex; flex-direction: column; gap: 0.15rem; }
    .sessions-list li button.active { border-color: #6c5ce7; background: #efe9ff; }
    .row-meta { font-size: 0.8rem; color: #6a708e; }
    .session-detail { background: #fff; border: 1px solid #d8d8e0; border-radius: 6px; padding: 1rem; }
    .meta { color: #6a708e; font-size: 0.9rem; margin-top: 0.2rem; }
    .member-list { list-style: none; padding: 0; }
    .member-list li { padding: 0.3rem 0; display: flex; align-items: center; gap: 0.5rem; }
    .slot-pill { display: inline-block; background: #6c5ce7; color: #fff; border-radius: 999px; padding: 0.05rem 0.55rem; font-size: 0.8rem; min-width: 2.4em; text-align: center; }
    .view-table { width: 100%; border-collapse: collapse; }
    .view-table th, .view-table td { padding: 0.3rem 0.5rem; border-bottom: 1px solid #efeff4; text-align: left; font-size: 0.9rem; }
    .muted { color: #6a708e; font-style: italic; }
    .small { font-size: 0.85rem; }
    .btn { padding: 0.3rem 0.7rem; border-radius: 4px; border: 1px solid #6c5ce7; background: #efe9ff; cursor: pointer; }
  `],
})
export class SessionsRemoteComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);

  sessions$: Observable<GameSession[]> = of([]);
  selected: GameSession | null = null;
  /** slot_index → most-recent ViewSample */
  viewSamples = new Map<number, ViewSample>();
  private es: EventSource | null = null;

  ngOnInit(): void { this.refreshList(); }
  ngOnDestroy(): void { this.closeStream(); }

  refreshList(): void {
    this.sessions$ = this.http.get<GameSession[]>('/api/sessions').pipe(
      catchError(() => of([])),
    );
  }

  select(s: GameSession): void {
    if (this.selected?.id === s.id) return;
    this.selected = s;
    this.viewSamples.clear();
    this.closeStream();
    // EventSource on the per-session admin stream. The four channels
    // are isolated to this session id by GameStateService's keyed sinks.
    this.es = new EventSource(`/api/admin/sessions/${s.id}/stream`);
    this.es.addEventListener('view', e => {
      try {
        const sample: ViewSample = JSON.parse((e as MessageEvent).data);
        this.viewSamples.set(sample.slotIndex, sample);
        // Force change detection by reassigning the map ref.
        this.viewSamples = new Map(this.viewSamples);
      } catch { /* ignore malformed */ }
    });
    this.es.addEventListener('movement', () => { /* could render trail later */ });
    this.es.onerror = () => console.warn('[SessionsShell] SSE error on session', s.id);
  }

  viewSampleArray(): ViewSample[] {
    return Array.from(this.viewSamples.values()).sort((a, b) => a.slotIndex - b.slotIndex);
  }

  private closeStream(): void {
    if (this.es) { this.es.close(); this.es = null; }
  }
}
