import { Component, OnInit, inject, ChangeDetectionStrategy } from '@angular/core';

import { HttpClient } from '@angular/common/http';
import { Router, RouterModule } from '@angular/router';
import { AuthService } from '../services/auth.service';

interface AdminAction {
  id: number;
  targetCasdoorId: string;
  actorCasdoorId: string;
  action: 'kick' | 'ban' | 'unban';
  message: string | null;
  expiresAt: string | null;
  createdAt: string;
}

interface BanStatus {
  banned: boolean;
  action?: AdminAction;
}

/**
 * Static-route page shown to a banned user. Reads
 * {@code /api/me/ban-status} on mount; if the latest action is a
 * non-active ban (i.e. unbanned or expired) it redirects back to the
 * home page so the user doesn't get stuck.
 *
 * <p>The page is intentionally accessible (semantic landmarks,
 * adequate contrast, no JS-only navigation) so a screen reader user
 * still understands what happened and how to appeal.
 */
@Component({
  selector: 'app-banned-page',
  standalone: true,
  imports: [RouterModule],
  template: `
    <main class="banned-page" role="main" aria-labelledby="banned-title">
      <section class="banned-card">
        <div class="banned-icon" aria-hidden="true">⚠️</div>
        <h1 id="banned-title" class="banned-title">{{ heading() }}</h1>

        @if (action(); as a) {
          <p class="banned-summary">
            {{ summary(a) }}
          </p>
          @if (a.message) {
            <blockquote class="banned-msg" aria-label="Message from the moderator">
              {{ a.message }}
            </blockquote>
          }
          <dl class="banned-meta">
            <dt>Action</dt><dd>{{ a.action }}</dd>
            <dt>When</dt><dd><time [attr.datetime]="a.createdAt">{{ humanWhen(a.createdAt) }}</time></dd>
            @if (a.expiresAt) {
              <dt>Expires</dt><dd><time [attr.datetime]="a.expiresAt">{{ humanWhen(a.expiresAt) }}</time></dd>
            }
          </dl>
        } @else if (loading) {
          <p class="banned-summary">Checking your account status…</p>
        } @else {
          <p class="banned-summary">
            We couldn't load the moderator action attached to your
            account. Try signing out and back in.
          </p>
        }

        <div class="banned-actions">
          <button type="button" class="btn-primary"
                  (click)="signOut()">
            Sign out
          </button>
          <a routerLink="/about" class="btn-secondary">
            Read the community guidelines
          </a>
        </div>
      </section>
    </main>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    :host { display: block; }
    .banned-page {
      min-height: calc(100vh - 64px);
      display: grid;
      place-items: center;
      padding: 32px 16px;
      background: linear-gradient(180deg, #0a0a12 0%, #12121e 100%);
      color: #e8e8f0;
    }
    .banned-card {
      width: min(560px, 100%);
      background: rgba(255, 90, 90, 0.06);
      border: 1px solid rgba(255, 90, 90, 0.45);
      border-radius: 12px;
      padding: 28px;
      box-shadow: 0 18px 60px rgba(0, 0, 0, 0.6);
    }
    .banned-icon {
      font-size: 3rem;
      text-align: center;
      margin-bottom: 8px;
    }
    .banned-title {
      margin: 0 0 12px;
      text-align: center;
      font-size: 1.6rem;
      color: #ff6b6b;
    }
    .banned-summary {
      margin: 0 0 16px;
      font-size: 1rem;
      line-height: 1.5;
      color: #d8dde6;
    }
    .banned-msg {
      margin: 12px 0 16px;
      padding: 12px 16px;
      border-left: 3px solid #ff6b6b;
      background: rgba(0, 0, 0, 0.25);
      color: #f4f4f8;
      font-style: italic;
    }
    .banned-meta {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 4px 12px;
      font-size: 0.86rem;
      margin: 16px 0 24px;
      color: #aaa;
    }
    .banned-meta dt {
      font-weight: 600;
      color: #b388ff;
    }
    .banned-actions {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      justify-content: center;
    }
    .btn-primary, .btn-secondary {
      appearance: none;
      border: 0;
      padding: 10px 18px;
      border-radius: 6px;
      font: inherit;
      cursor: pointer;
      text-decoration: none;
      display: inline-flex;
      align-items: center;
      gap: 6px;
    }
    .btn-primary {
      background: #7c4dff;
      color: white;
    }
    .btn-primary:hover, .btn-primary:focus-visible {
      background: #b388ff;
      outline: none;
    }
    .btn-secondary {
      background: rgba(255, 255, 255, 0.06);
      color: #d8dde6;
      border: 1px solid rgba(255, 255, 255, 0.12);
    }
    .btn-secondary:hover, .btn-secondary:focus-visible {
      background: rgba(255, 255, 255, 0.12);
      outline: none;
    }
  `],
})
export class BannedPageComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);
  private readonly auth = inject(AuthService);

  loading = true;
  status: BanStatus | null = null;

  ngOnInit(): void {
    if (!this.auth.isAuthenticated()) {
      this.router.navigate(['/login']);
      return;
    }
    this.http.get<BanStatus>('/api/me/ban-status').subscribe({
      next: s => {
        this.status = s;
        this.loading = false;
        if (!s.banned) {
          // Not actually banned (e.g. came here mid-unban) — bounce home.
          this.router.navigate(['/']);
        }
      },
      error: _ => {
        this.loading = false;
      },
    });
  }

  action(): AdminAction | null {
    return this.status?.action ?? null;
  }

  heading(): string {
    const a = this.action();
    if (!a) return 'Account suspended';
    if (a.action === 'ban') return 'Your account is banned';
    if (a.action === 'kick') return 'You were removed from the session';
    return 'Account suspended';
  }

  summary(a: AdminAction): string {
    if (a.action === 'kick') {
      return 'A moderator removed you from your active session. You can sign in again to rejoin.';
    }
    if (a.expiresAt) {
      return 'A moderator has temporarily suspended your access.';
    }
    return 'A moderator has suspended your access. Contact the moderation team to appeal.';
  }

  humanWhen(iso: string): string {
    return new Date(iso).toLocaleString();
  }

  signOut(): void {
    this.auth.logout();
    this.router.navigate(['/login']);
  }
}
