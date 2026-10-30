import { HttpErrorResponse } from '@angular/common/http';
import { Component, ElementRef, OnDestroy, OnInit, computed, inject, signal, viewChild } from '@angular/core';
import { DomSanitizer } from '@angular/platform-browser';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiFailure, GamesApiService, classifyFailure } from './games-api.service';
import { NEW_TAB_REASON, NEW_TAB_SLUGS, bundleUrl, mountRoute } from './games.constants';
import { GamesForbiddenComponent, GamesUnreachableComponent } from './status-panels.component';

export const IN_SHELL_NOTE =
  'Multiplayer and relay features are not available for in-shell play on this mesh.';

/** Never grow the frame past this (a bundle whose height tracks its own
 *  viewport would otherwise chase the frame). */
export const MAX_FRAME_PX = 2400;

/**
 * The height the in-shell frame needs: the bundle's content height when that is
 * taller than the frame's CSS height, else null (keep the CSS height). Pure, so
 * the sizing rule is testable without a real document.
 *
 * @param cssHeight     the frame's current CSS-sized height in px
 * @param contentHeight the bundle document's scrollHeight in px
 */
export function fittedFrameHeight(cssHeight: number, contentHeight: number): number | null {
  if (!Number.isFinite(contentHeight) || contentHeight <= cssHeight + 1) return null;
  return Math.min(Math.ceil(contentHeight), MAX_FRAME_PX);
}

export type PlayState =
  | 'launching'
  | 'ready'
  | 'not-available'
  | 'unknown'
  | 'bundle-refused'
  | 'forbidden'
  | 'unreachable';

/**
 * M2 Play — path 'play/:slug' (host mount /games/play/<slug>). Launches one
 * game and plays it inside the shell: POST /games/api/games/{slug}/launch, then
 * a same-origin <iframe> of the bundle document with the launch uid. The five
 * SharedArrayBuffer bundles (NEW_TAB_SLUGS) open top-level in a new tab instead.
 * View state is signals (zoneless host).
 */
@Component({
  selector: 'hug-games-play',
  standalone: true,
  imports: [RouterLink, GamesForbiddenComponent, GamesUnreachableComponent],
  templateUrl: './play.component.html',
  styleUrl: './play.component.scss',
})
export class GamePlayComponent implements OnInit, OnDestroy {
  private readonly api = inject(GamesApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly sanitizer = inject(DomSanitizer);

  readonly frame = viewChild<ElementRef<HTMLIFrameElement>>('frame');

  readonly mount = mountRoute(this.route);
  readonly newTabReason = NEW_TAB_REASON;
  readonly inShellNote = IN_SHELL_NOTE;

  slug = '';
  readonly name = signal('');
  readonly mode = signal<'here' | 'new-tab'>('here');
  readonly state = signal<PlayState>('launching');
  readonly failure = signal<ApiFailure | null>(null);
  readonly uid = signal<string | null>(null);
  readonly expiresAt = signal<string | null>(null);
  /** The X-Resource-Denied value the gateway sent for the bundle, when any. */
  readonly bundleDenied = signal<string | null>(null);
  readonly frameUrl = signal<string | null>(null);
  /** Frame height in px when the bundle needs more than the CSS height; null
   *  keeps the CSS height (see play.component.scss). */
  readonly frameHeight = signal<number | null>(null);
  private frameObserver: ResizeObserver | null = null;
  /** Overflow (content minus frame, px) seen at the last growth; null = none yet. */
  private lastOverflow: number | null = null;
  /** The iframe src. Angular treats <iframe src> as a RESOURCE_URL context;
   *  the value is same-origin and built only from an encoded slug + uid
   *  (bundleUrl), never from a server-supplied string, so trusting it is safe. */
  readonly frameSrc = computed(() => {
    const url = this.frameUrl();
    return url ? this.sanitizer.bypassSecurityTrustResourceUrl(url) : null;
  });

  ngOnInit(): void {
    this.slug = this.route.snapshot.paramMap.get('slug') ?? '';
    this.name.set(this.slug);
    this.mode.set(NEW_TAB_SLUGS.includes(this.slug) ? 'new-tab' : 'here');
    this.api.listGames().subscribe({
      next: (games) => {
        const g = (games ?? []).find((x) => x.slug === this.slug);
        if (g) this.name.set(g.name);
      },
      error: () => {
        /* name stays the slug; the launch call decides the state */
      },
    });
    const resumeUid = this.route.snapshot.queryParamMap.get('uid');
    if (resumeUid) {
      // Resume from the catalog's recent-launches strip: reuse the unexpired uid.
      this.uid.set(resumeUid);
      this.expiresAt.set(this.route.snapshot.queryParamMap.get('exp'));
      this.afterLaunch();
      return;
    }
    this.launch();
  }

  launch(): void {
    this.state.set('launching');
    this.failure.set(null);
    this.api.launch(this.slug).subscribe({
      next: (res) => {
        this.uid.set(res.uid);
        this.expiresAt.set(res.expiresAt);
        this.afterLaunch();
      },
      error: (err) => {
        const f = classifyFailure(err);
        this.failure.set(f);
        switch (f.kind) {
          case 'forbidden':
            // 403 = game_access policy (or 401 = no session at all). The policy
            // message is the honest one for a signed-in member; an anonymous
            // caller never reaches this route (gamesGuard).
            this.state.set(f.status === 403 ? 'not-available' : 'forbidden');
            break;
          case 'notFound':
            this.state.set('unknown');
            break;
          default:
            this.state.set('unreachable');
        }
      },
    });
  }

  /** With a uid in hand: new-tab games are ready; in-shell games are probed
   *  first so a gateway refusal of the bundle is text, not a blank frame. */
  private afterLaunch(): void {
    if (this.mode() === 'new-tab') {
      this.state.set('ready');
      return;
    }
    const url = bundleUrl(this.slug, this.uid());
    this.api.probeBundle(url).subscribe({
      next: () => {
        this.frameUrl.set(url);
        this.state.set('ready');
      },
      error: (err) => {
        if (err instanceof HttpErrorResponse && (err.status === 403 || err.status === 401)) {
          this.bundleDenied.set(
            err.headers?.get('X-Resource-Denied') ??
              err.headers?.get('X-Route-Denied') ??
              `game:${this.slug}`,
          );
          this.state.set('bundle-refused');
          return;
        }
        if (err instanceof HttpErrorResponse && err.status === 404) {
          this.state.set('unknown');
          return;
        }
        this.failure.set(classifyFailure(err));
        this.state.set('unreachable');
      },
    });
  }

  /**
   * The bundle loaded: grow the frame to its content when the content is taller
   * than the frame, and keep doing so while the content resizes (fonts, a
   * wrapping header). The bundle is same-origin (/_remotes/wasm-fragment/**),
   * so its document is readable; if it ever is not, the CSS height stands.
   * A growth that leaves the overflow as large as before means the content
   * tracks the frame's own height (a board sized to the viewport); growing
   * again would only chase it, so it stops there.
   */
  fitFrame(): void {
    const iframe = this.frame()?.nativeElement;
    this.frameObserver?.disconnect();
    this.frameObserver = null;
    this.lastOverflow = null;
    let doc: Document | null = null;
    try {
      doc = iframe?.contentDocument ?? null;
    } catch {
      doc = null; // cross-origin: keep the CSS height
    }
    if (!iframe || !doc?.documentElement) return;
    const measure = () => {
      const contentHeight = doc!.documentElement.scrollHeight;
      const next = fittedFrameHeight(iframe.clientHeight, contentHeight);
      if (next === null) {
        this.lastOverflow = null; // fits: a later overflow is new, not a chase
        return;
      }
      const overflow = contentHeight - iframe.clientHeight;
      if (this.lastOverflow !== null && overflow >= this.lastOverflow) return;
      this.lastOverflow = overflow;
      this.frameHeight.set(next);
    };
    measure();
    // The bundle's own window observes its own document (its rendering loop
    // delivers the notifications), and the growth runs on the next frame, not
    // inside the callback: growing the frame resizes the observed <html>, and
    // doing that inside the callback is a ResizeObserver loop error.
    const view = doc.defaultView as (Window & typeof globalThis) | null;
    const RO = view?.ResizeObserver ?? (typeof ResizeObserver !== 'undefined' ? ResizeObserver : undefined);
    if (RO) {
      const raf = view?.requestAnimationFrame?.bind(view) ?? requestAnimationFrame;
      this.frameObserver = new RO(() => raf(() => measure()));
      this.frameObserver.observe(doc.documentElement);
      if (doc.body) this.frameObserver.observe(doc.body);
    }
  }

  /** The <iframe> fired `error` (navigation refused after the probe passed). */
  onFrameError(): void {
    this.bundleDenied.update((d) => d ?? `game:${this.slug}`);
    this.state.set('bundle-refused');
  }

  openInNewTab(): void {
    const uid = this.uid();
    if (!uid) return;
    // noopener: the bundle gets its own browsing context (and its own
    // COOP/COEP isolation) with no handle back to the shell.
    window.open(bundleUrl(this.slug, uid), '_blank', 'noopener');
  }

  fullscreen(): void {
    const el = this.frame()?.nativeElement;
    if (el?.requestFullscreen) {
      el.requestFullscreen().catch(() => {
        /* denied by the browser — nothing to surface */
      });
    }
  }

  ngOnDestroy(): void {
    this.frameObserver?.disconnect();
    // Best-effort: tell the BFF the launch is over. Errors are ignored — the
    // launch expires on its own TTL anyway.
    const uid = this.uid();
    if (uid) {
      this.api.terminateLaunch(uid).subscribe({ next: () => {}, error: () => {} });
    }
  }
}
