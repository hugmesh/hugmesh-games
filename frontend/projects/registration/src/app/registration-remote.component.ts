import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';

/**
 * Federated registration UI — entry point for new users signing up
 * via an external OAuth provider (GitHub today; the same surface
 * accommodates Google / OIDC providers Casdoor exposes).
 *
 * <p>Flow:
 *
 * <ol>
 *   <li>User lands at <code>/registration</code> via the host shell.</li>
 *   <li>Clicks "Continue with GitHub" — redirects through Casdoor's
 *       authorization endpoint with {@code provider=github}.</li>
 *   <li>Casdoor lands back at /login/oauth2/code/casdoor with an
 *       access_token + the user's external identityProvider claims.</li>
 *   <li>This component's resumed-load handler POSTs
 *       {githubAccessToken, snapdragonUsername} to
 *       <code>/api/registration/github-org-admin/evaluate</code>
 *       (snapdragon-actions service, proxied by the gateway).</li>
 *   <li>On 200 → navigate to the catalog. On 403 → render the
 *       "no Snapdragon org admin" message in-page (the user is
 *       still registered as a regular player; only the admin
 *       auto-grant was denied).</li>
 * </ol>
 *
 * <p>Why HttpClient + Router are injected directly: the host's
 * AuthService is in the host bundle and is NOT shared via the
 * federation manifest. HttpClient + Router ARE shared
 * (`shareAll({singleton: true})` in federation.config.js) so the
 * authenticated session the host already established here.
 */
@Component({
  selector: 'app-registration-remote',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="reg-page">
      <div class="reg-card">
        <h1>Snapdragon <span>Sign up</span></h1>
        <p class="subtitle">Register for the portal with an existing
          provider account — we read your basic profile, never the
          private bits.</p>

        @if (status() === 'idle') {
          <button class="provider-btn"
                  type="button"
                  (click)="continueWithGitHub()">
            <span aria-hidden="true">⌘</span>
            Continue with GitHub
          </button>
          <p class="hint">
            If you administer a repo in any Snapdragon GitHub
            organisation you'll be auto-granted admin in
            snapdragon.systems — otherwise you'll land as a regular
            player with no extra fuss.
          </p>
        }
        @else if (status() === 'evaluating') {
          <p class="info">Checking your GitHub org membership…</p>
        }
        @else if (status() === 'granted') {
          <p class="success">Welcome — you've been granted admin access.</p>
        }
        @else if (status() === 'denied') {
          <p class="info">You're registered. No Snapdragon admin
            permissions detected — that's fine, you can play
            everything as a regular user.</p>
          <button class="provider-btn"
                  type="button"
                  (click)="goToCatalog()">Continue to catalog</button>
        }
        @else if (status() === 'error') {
          <p class="error">{{ errorMessage() }}</p>
        }
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    :host { display: block; min-height: 100vh; background: #0a0a12; }
    .reg-page {
      min-height: 100vh; display: flex; align-items: center;
      justify-content: center; padding: 24px;
      background-image: radial-gradient(
        ellipse at 50% 30%,
        rgba(124, 77, 255, 0.15) 0%,
        transparent 65%);
    }
    .reg-card {
      background: #12121e; border: 1px solid #1e1e32;
      border-radius: 16px; padding: 48px 40px; width: 100%;
      max-width: 440px; text-align: center; color: #e8e8f0;
      box-shadow: 0 24px 64px #0009, 0 0 0 1px #7c4dff1a;
    }
    h1 { font-size: 1.8rem; font-weight: 800; margin: 0 0 12px; }
    h1 span { background: linear-gradient(135deg,#7c4dff,#b388ff);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent; }
    .subtitle, .hint { color: #a8acc4; font-size: 0.95rem;
                       line-height: 1.5; margin: 0 0 24px; }
    .hint { font-size: 0.85rem; margin-top: 20px; }
    .provider-btn {
      display: inline-flex; align-items: center; justify-content: center;
      gap: 10px; background: #7c4dff; color: #fff; border: none;
      border-radius: 8px; padding: 14px 32px; font-size: 1rem;
      font-weight: 600; cursor: pointer; width: 100%;
      transition: background .2s, transform .1s;
    }
    .provider-btn:hover { background: #8b5cff; }
    .info    { color: #b388ff; }
    .success { color: #4caf50; font-weight: 600; }
    .error   { color: #ff6b6b; }
  `],
})
export class RegistrationRemoteComponent {

  private http   = inject(HttpClient);
  private router = inject(Router);

  /** Finite-state for the page. Bound directly into the template. */
  readonly status = signal<'idle' | 'evaluating' | 'granted' | 'denied' | 'error'>('idle');
  readonly errorMessage = signal<string>('');

  constructor() {
    // On reload, if we have a fragment / query carrying the external
    // OAuth callback shape, resume the evaluate step.
    const params = new URLSearchParams(window.location.search);
    const accessToken = params.get('access_token');
    const username    = params.get('username');
    if (accessToken && username) {
      this.evaluateGitHubAdmin(accessToken, username);
    }
  }

  continueWithGitHub(): void {
    // The Casdoor instance fronting this stack proxies the GitHub
    // OAuth flow. After the user grants access on GitHub, Casdoor
    // lands them back at /registration with the access_token +
    // username in the query string. The exact authorization URL is
    // a Casdoor admin-config detail — keep the redirect in one
    // place so a Casdoor flip doesn't touch the component.
    const redirect = encodeURIComponent(`${window.location.origin}/registration`);
    window.location.href =
      `/api/auth/oauth-redirect?provider=github&redirect=${redirect}`;
  }

  goToCatalog(): void {
    this.router.navigateByUrl('/');
  }

  private evaluateGitHubAdmin(githubAccessToken: string, snapdragonUsername: string): void {
    this.status.set('evaluating');
    this.http
      .post<{ memberId: string }>(
        '/api/registration/github-org-admin/evaluate',
        { githubAccessToken, snapdragonUsername })
      .subscribe({
        next: () => this.status.set('granted'),
        error: (err) => {
          if (err?.status === 403) {
            this.status.set('denied');
          } else {
            this.errorMessage.set(
              `Registration evaluation failed: ${err?.message ?? 'unknown error'}`);
            this.status.set('error');
          }
        },
      });
  }
}
