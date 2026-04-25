import { Component, EventEmitter, Output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

/**
 * Federated login form. Owns only the FORM; the host wires the actual
 * AuthService.login() call to the (submit) output, so this remote can
 * be replaced/reskinned without touching auth state. The host also
 * handles routing on success — keeps the auth flow centralised in
 * the shell where the JWT cookie + interceptor live.
 *
 * The host loads this via:
 *   loadRemoteModule({ remoteName: 'login', exposedModule: './LoginComponent' })
 * and registers it as the `<app-login-remote>` Web Component.
 */
@Component({
  selector: 'app-login-remote',
  standalone: true,
  imports: [ReactiveFormsModule],
  template: `
    <section class="login-shell">
      <h1 class="login-title">Snapdragon Games</h1>
      <p class="login-sub">Sign in to continue</p>

      <form class="login-form" [formGroup]="form" (ngSubmit)="onSubmit()">
        <label>
          <span>Username</span>
          <input formControlName="username" autocomplete="username" autofocus />
        </label>
        <label>
          <span>Password</span>
          <input type="password" formControlName="password" autocomplete="current-password" />
        </label>

        @if (errorMsg()) {
          <p class="login-error">{{ errorMsg() }}</p>
        }

        <button type="submit" class="login-btn" [disabled]="form.invalid || loading()">
          {{ loading() ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>

      <p class="login-source">Federated remote · <code>login</code></p>
    </section>
  `,
  styles: [`
    :host { display: block; max-width: 360px; margin: 0 auto; padding: 24px; }
    .login-shell { background: #131625; border: 1px solid #232a3d; border-radius: 10px;
                   padding: 24px; color: #d8dde6; font-family: sans-serif; }
    .login-title { font-size: 22px; margin: 0 0 4px; }
    .login-sub { margin: 0 0 16px; color: #8b94a8; font-size: 13px; }
    .login-form { display: flex; flex-direction: column; gap: 12px; }
    .login-form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; }
    .login-form input { background: #0f111a; border: 1px solid #2a3148; border-radius: 5px;
                        color: inherit; padding: 8px; font: inherit; }
    .login-form input:focus { outline: none; border-color: #6c63ff; }
    .login-btn { background: #6c63ff; color: #fff; border: 0; border-radius: 5px;
                 padding: 9px 12px; font-weight: 600; cursor: pointer; }
    .login-btn:disabled { opacity: 0.55; cursor: not-allowed; }
    .login-error { color: #ff6c6c; font-size: 12px; margin: 0; }
    .login-source { margin: 16px 0 0; font-size: 11px; color: #5a6274; text-align: center; }
    .login-source code { background: #1a1d28; padding: 1px 4px; border-radius: 3px; }
  `],
})
export class LoginRemoteComponent {
  readonly form = new FormGroup({
    username: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    password: new FormControl('', { nonNullable: true, validators: [Validators.required] }),
  });

  readonly loading = signal(false);
  readonly errorMsg = signal<string>('');

  /** Emitted when the user submits valid credentials. Host wires AuthService. */
  @Output() readonly submitCredentials = new EventEmitter<{ username: string; password: string }>();

  onSubmit(): void {
    if (this.form.invalid || this.loading()) return;
    this.loading.set(true);
    this.errorMsg.set('');
    this.submitCredentials.emit(this.form.getRawValue());
  }

  /** Host calls this when AuthService.login() rejects. */
  reportError(msg: string): void {
    this.errorMsg.set(msg);
    this.loading.set(false);
  }
}
