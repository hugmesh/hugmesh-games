import {
  Component,
  ElementRef,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  ApiFailure,
  Game,
  GameSession,
  GamesApiService,
  classifyFailure,
} from './games-api.service';
import { mountRoute } from './games.constants';
import { GamesAdminForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

/** The SSE channels AdminSessionStreamController merges, with a colour each. */
export const STREAM_CHANNELS: readonly { name: string; colour: string }[] = [
  { name: 'movement', colour: '#7cc5ff' },
  { name: 'world', colour: '#b388ff' },
  { name: 'character', colour: '#ffd54f' },
  { name: 'view', colour: '#80cbc4' },
  { name: 'session-control', colour: '#ff8a80' },
];

export interface TerminalLine {
  id: number;
  at: string;
  channel: string;
  colour: string;
  text: string;
}

const MAX_LINES = 500;

export type StreamState = 'idle' | 'open' | 'reconnecting' | 'forbidden';

/**
 * A2 Live sessions — paths 'sessions' (list) and 'sessions/:id' (list +
 * detail). One component serves both: the list is always rendered (left on
 * desktop, stacked on phone) and the detail terminal appears when the route
 * carries an id. The per-session SSE stream is opened through
 * GamesApiService.openSessionStream so specs never open a real connection.
 * View state is signals (zoneless host): EventSource callbacks write signals,
 * which is what schedules the repaint.
 */
@Component({
  selector: 'hug-games-admin-live',
  standalone: true,
  imports: [FormsModule, RouterLink, GamesAdminForbiddenComponent, GamesUnreachableComponent],
  templateUrl: './admin-live.component.html',
  styleUrl: './admin-live.component.scss',
})
export class GamesAdminLiveComponent implements OnInit, OnDestroy {
  private readonly api = inject(GamesApiService);
  private readonly route = inject(ActivatedRoute);

  readonly terminal = viewChild<ElementRef<HTMLElement>>('terminal');

  /** Links resolve against the admin mount (parent of the shell's '' route). */
  readonly mount = mountRoute(this.route.parent ?? this.route);
  readonly channels = STREAM_CHANNELS;

  readonly state = signal<'loading' | 'loaded' | 'forbidden' | 'unreachable'>('loading');
  readonly failure = signal<ApiFailure | null>(null);
  readonly sessions = signal<GameSession[]>([]);
  readonly games = signal<Game[]>([]);
  readonly gameFilter = signal('');
  readonly search = signal('');
  readonly actionError = signal<string | null>(null);
  readonly busy = signal<string | null>(null);

  readonly filtered = computed(() => {
    const q = this.search().trim().toLowerCase();
    const rows = this.sessions();
    if (!q) return rows;
    return rows.filter(
      (s) =>
        s.id.toLowerCase().includes(q) ||
        (s.name ?? '').toLowerCase().includes(q) ||
        s.gameSlug.toLowerCase().includes(q) ||
        s.creatorCasdoorId.toLowerCase().includes(q),
    );
  });

  // ── detail ──
  readonly selectedId = signal<string | null>(null);
  readonly selected = computed(() => this.sessions().find((s) => s.id === this.selectedId()) ?? null);
  readonly lines = signal<TerminalLine[]>([]);
  readonly enabled = signal<Record<string, boolean>>(
    Object.fromEntries(STREAM_CHANNELS.map((c) => [c.name, true])),
  );
  readonly autoscroll = signal(true);
  readonly streamState = signal<StreamState>('idle');
  readonly streamFailure = signal<ApiFailure | null>(null);
  readonly recoverArmed = signal(false);
  readonly recoverResult = signal<string | null>(null);
  readonly visibleLines = computed(() => {
    const on = this.enabled();
    return this.lines().filter((l) => on[l.channel] !== false);
  });

  private source: EventSource | null = null;
  private lineId = 0;
  private paramSub?: Subscription;

  ngOnInit(): void {
    this.load();
    this.api.listGames().subscribe({
      next: (g) => this.games.set(g ?? []),
      error: () => this.games.set([]),
    });
    this.paramSub = this.route.paramMap.subscribe((pm) => this.select(pm.get('id')));
  }

  load(): void {
    this.state.set('loading');
    this.failure.set(null);
    this.api.multiplayerSessions(this.gameFilter() || null).subscribe({
      next: (rows) => {
        this.sessions.set(rows ?? []);
        this.state.set('loaded');
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        this.state.set(f.kind === 'forbidden' ? 'forbidden' : 'unreachable');
      },
    });
  }

  onGameFilter(slug: string): void {
    this.gameFilter.set(slug);
    this.load();
  }

  toggleChannel(name: string, on: boolean): void {
    this.enabled.update((e) => ({ ...e, [name]: on }));
  }

  togglePause(s: GameSession): void {
    this.actionError.set(null);
    this.busy.set(s.id);
    const call = s.pausedAt ? this.api.resumeSession(s.id) : this.api.pauseSession(s.id, 'admin oversight');
    call.subscribe({
      next: (updated) => {
        this.sessions.update((rows) => rows.map((x) => (x.id === updated.id ? updated : x)));
        this.busy.set(null);
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.actionError.set(
          f.kind === 'forbidden'
            ? `The gateway refused ${s.pausedAt ? 'resuming' : 'pausing'} ${s.id} (${f.status}).`
            : `Could not ${s.pausedAt ? 'resume' : 'pause'} ${s.id} (${f.status || 'network'}).`,
        );
        this.busy.set(null);
      },
    });
  }

  // ── detail / stream ──
  private select(id: string | null): void {
    if (id === this.selectedId()) return;
    this.closeStream();
    this.selectedId.set(id);
    this.lines.set([]);
    this.recoverArmed.set(false);
    this.recoverResult.set(null);
    this.streamFailure.set(null);
    if (id) this.openStream(id);
    else this.streamState.set('idle');
  }

  private openStream(id: string): void {
    // A 403 at open is not observable on EventSource itself (it only fires a
    // generic `error`), so probe the stream URL once with HttpClient first: the
    // gateway answers 401/403 with X-Route-Denied, which is what the forbidden
    // panel shows. Only on a 2xx is the long-lived EventSource opened.
    this.streamState.set('reconnecting');
    this.api.probeStream(id).subscribe({
      next: () => this.attach(id),
      error: (err) => {
        const f = classifyFailure(err);
        if (f.kind === 'forbidden') {
          this.streamFailure.set(f);
          this.streamState.set('forbidden');
          return;
        }
        // Not forbidden (e.g. 404 unknown session): let EventSource try and
        // auto-retry; the banner stays up until it opens.
        this.attach(id);
      },
    });
  }

  private attach(id: string): void {
    let es: EventSource;
    try {
      es = this.api.openSessionStream(id);
    } catch {
      this.streamState.set('reconnecting');
      return;
    }
    this.source = es;
    es.onopen = () => this.streamState.set('open');
    es.onerror = () => this.streamState.set('reconnecting');
    for (const c of STREAM_CHANNELS) {
      es.addEventListener(c.name, (ev: Event) => {
        const data = (ev as MessageEvent).data;
        this.push(c.name, c.colour, typeof data === 'string' ? data : String(data));
      });
    }
  }

  private push(channel: string, colour: string, text: string): void {
    const line: TerminalLine = {
      id: ++this.lineId,
      at: new Date().toISOString().slice(11, 19),
      channel,
      colour,
      text,
    };
    this.lines.update((ls) => {
      const next = [...ls, line];
      return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next;
    });
    if (this.autoscroll()) {
      // After the next paint: scroll the terminal to the bottom.
      setTimeout(() => {
        const el = this.terminal()?.nativeElement;
        if (el) el.scrollTop = el.scrollHeight;
      });
    }
  }

  clear(): void {
    this.lines.set([]);
  }

  recover(): void {
    const id = this.selectedId();
    if (!id) return;
    if (!this.recoverArmed()) {
      this.recoverArmed.set(true);
      return;
    }
    this.api.recoverRealtime(id).subscribe({
      next: () => {
        this.recoverResult.set(`Recovery requested for ${id}.`);
        this.recoverArmed.set(false);
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.recoverResult.set(
          f.kind === 'forbidden'
            ? `The gateway refused the recovery call (${f.status}).`
            : `Recovery call failed (${f.status || 'network'}).`,
        );
        this.recoverArmed.set(false);
      },
    });
  }

  cancelRecover(): void {
    this.recoverArmed.set(false);
  }

  private closeStream(): void {
    if (this.source) {
      this.source.close();
      this.source = null;
    }
  }

  ngOnDestroy(): void {
    this.paramSub?.unsubscribe();
    this.closeStream();
  }
}
