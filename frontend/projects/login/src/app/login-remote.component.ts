import { Component, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Router } from '@angular/router';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

interface TokenResponse {
  access_token: string;
  id_token?: string;
  token_type: string;
  expires_in: number;
}

// Storage key MUST match the host's AuthService.STORAGE_KEY so the host's
// HTTP interceptor + auth guard pick up the token after this remote
// redirects to /. If the host changes its key, this remote has to follow.
const STORAGE_KEY = 'tw_auth';

/**
 * Federated login UI — full page form with the same branding as the
 * host's legacy LoginComponent. Loaded by the host's `/login` route via
 *
 *   loadComponent: () =>
 *     loadRemoteModule({ remoteName: 'login', exposedModule: './LoginComponent' })
 *       .then(m => m.LoginRemoteComponent)
 *
 * Why HttpClient + Router are injected directly here (instead of
 * delegating to the host's AuthService): AuthService is in the host
 * bundle and is NOT shared via the federation manifest. HttpClient and
 * Router ARE shared (`shareAll({singleton: true})` in
 * federation.config.js), so they reach this remote through the host's
 * env injector. The token shape we write into localStorage mirrors
 * AuthService.StoredAuth exactly — the interceptor + guard read the
 * same key on the next request, no second authentication round.
 */
@Component({
  selector: 'app-login-remote',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <div class="login-page">
      <div class="login-card">
        <!-- Brand glyph — the same dragon as the host nav brand
             ('🐉 HugMesh Games') and assets/icon.svg, so the login
             card, tab icon, and shell chrome carry one mark. -->
        <div class="logo-area">
          <div class="logo-icon">&#128009;</div>
        </div>
        <h1 class="game-title">HugMesh<br><span>Games</span></h1>
        <p class="subtitle">Sign in to enter the portal</p>

        <form [formGroup]="form" (ngSubmit)="submit()" class="login-form"
              aria-describedby="login-status">
          <div class="field">
            <label for="login-username" class="sr">Username</label>
            <input
              id="login-username"
              formControlName="username"
              type="text"
              placeholder="Username"
              class="input"
              autocomplete="username"
              [attr.aria-invalid]="form.controls.username.invalid && form.controls.username.touched ? 'true' : null"
              required>
          </div>
          <div class="field">
            <label for="login-password" class="sr">Password</label>
            <input
              id="login-password"
              formControlName="password"
              type="password"
              placeholder="Password"
              class="input"
              autocomplete="current-password"
              [attr.aria-invalid]="form.controls.password.invalid && form.controls.password.touched ? 'true' : null"
              required>
          </div>

          <!-- Live region so screen readers announce auth failures
               without the user having to re-tab to find the error.
               role=alert implies aria-live=assertive + aria-atomic=true. -->
          <p id="login-status" class="error-msg"
             role="alert" aria-live="assertive"
             [class.empty]="!errorMsg()">
            {{ errorMsg() }}
          </p>

          <button type="submit" class="login-btn" [disabled]="form.invalid || loading()">
            @if (loading()) {
              <span class="btn-icon" aria-hidden="true">&#8987;</span>
              Signing in&hellip;
            } @else {
              <span class="btn-icon" aria-hidden="true">&#128481;</span>
              Sign In
            }
          </button>
        </form>

        <p class="login-note">
          A JRPG game portal. Up to 3 players, one controller.
          <span class="login-source">Federated remote · <code>login</code></span>
        </p>
      </div>
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.Eager,
  styles: [`
    :host { display: block; background: #0a0a12; min-height: 100vh; }
    .login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center;
                  background: #0a0a12; padding: 24px;
                  background-image: radial-gradient(ellipse at 50% 30%, rgba(124,77,255,0.15) 0%, transparent 65%); }
    .login-card { background: #12121e; border: 1px solid #1e1e32; border-radius: 16px;
                  padding: 48px 40px; width: 100%; max-width: 400px; text-align: center;
                  box-shadow: 0 24px 64px rgba(0,0,0,0.6), 0 0 0 1px rgba(124,77,255,0.1); }
    .logo-area { margin-bottom: 24px; }
    .logo-icon { font-size: 3rem; line-height: 1; filter: drop-shadow(0 0 12px rgba(124,77,255,0.6)); }
    .game-title { font-size: 1.8rem; font-weight: 800; letter-spacing: -0.02em; line-height: 1.2;
                  margin: 0 0 12px; color: #e8e8f0; }
    .game-title span { background: linear-gradient(135deg, #7c4dff 0%, #b388ff 100%);
                       -webkit-background-clip: text; -webkit-text-fill-color: transparent;
                       background-clip: text; }
    .subtitle { color: #a8acc4; font-size: 0.95rem; margin: 0 0 32px; line-height: 1.5; }
    .login-form { display: flex; flex-direction: column; gap: 12px; }
    .field { text-align: left; }
    .input { width: 100%; background: rgba(255,255,255,0.04); border: 1px solid #1e1e32;
             border-radius: 8px; padding: 12px 16px; color: #e8e8f0; font-size: 0.95rem;
             outline: none; box-sizing: border-box;
             transition: border-color 0.2s, box-shadow 0.2s; }
    .input::placeholder { color: #a8acc4; }
    .input:focus { border-color: #7c4dff; box-shadow: 0 0 0 2px rgba(124,77,255,0.2); }
    .error-msg { color: #ff6b6b; font-size: 0.85rem; margin: 0; text-align: left;
                 min-height: 1.2em; /* reserve space so the layout doesn't jump
                                       when the live-region populates */ }
    .error-msg.empty { color: transparent; }   /* render nothing visually until
                                                  there's content, but keep the
                                                  live region in the DOM so the
                                                  screen reader announces the
                                                  next assertion */
    /* Screen-reader-only labels — visible to AT but visually hidden.
       Used for the username/password inputs whose placeholder was
       the only label before (placeholders disappear on focus and
       are not accessible labels). */
    .sr {
      position: absolute;
      width: 1px; height: 1px;
      margin: -1px; padding: 0; overflow: hidden;
      clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0;
    }
    .login-btn { display: inline-flex; align-items: center; justify-content: center; gap: 10px;
                 background: #7c4dff; color: #fff; border: none; border-radius: 8px;
                 padding: 14px 32px; font-size: 1rem; font-weight: 600; cursor: pointer;
                 width: 100%; letter-spacing: 0.02em;
                 transition: background 0.2s, box-shadow 0.2s, transform 0.1s; }
    .login-btn:hover:not(:disabled) { background: #8b5cff; box-shadow: 0 4px 20px rgba(124,77,255,0.45);
                                      transform: translateY(-1px); }
    .login-btn:disabled { opacity: 0.6; cursor: not-allowed; }
    .btn-icon { font-size: 1.1rem; }
    .login-note { color: #a8acc4; font-size: 0.75rem; margin: 20px 0 0; opacity: 0.7; line-height: 1.6; }
    .login-source { display: block; margin-top: 6px; font-size: 0.7rem; opacity: 0.85; }
    .login-source code { background: rgba(255,255,255,0.05); padding: 1px 5px; border-radius: 3px; }
  `],
})
export class LoginRemoteComponent {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  readonly form = new FormGroup({
    username: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  readonly loading = signal(false);
  readonly errorMsg = signal<string>('');

  submit(): void {
    if (this.form.invalid || this.loading()) return;
    this.loading.set(true);
    this.errorMsg.set('');

    const { username, password } = this.form.getRawValue();
    this.http.post<TokenResponse>('/api/auth/token', { username, password }).subscribe({
      next: resp => {
        // Mirror AuthService.StoredAuth exactly — same key, same fields,
        // so the host's interceptor + guard see this token without a
        // second login round. Storage backend is `localStorage` (PR #57
        // moved it off sessionStorage so middle-click/Ctrl-click "open in
        // new tab" on any menu link survives the per-tab storage isolation).
        localStorage.setItem(STORAGE_KEY, JSON.stringify({
          accessToken: resp.access_token,
          idToken: resp.id_token ?? '',
          expiry: Date.now() + resp.expires_in * 1000,
        }));
        this.router.navigate(['/']);
      },
      error: err => {
        this.errorMsg.set(err.error?.msg ?? err.error?.message ?? 'Invalid credentials');
        this.loading.set(false);
      },
    });
  }
}
