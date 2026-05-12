import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ReactiveFormsModule, FormControl, FormGroup, Validators } from '@angular/forms';
import { HttpClient } from '@angular/common/http';

/**
 * Federated registration form for game producers + mods. An
 * authenticated player submits the form to apply for elevated
 * producer / mod authority; the request lands in
 * {@code producer_contacts} (Liquibase 031) with status=PENDING
 * for admin review. Admins later flip the row to APPROVED via an
 * admin endpoint and grant the appropriate Casdoor role.
 *
 * <p>The producer / mod selection is part of the form because both
 * paths reuse the same intake schema: a stable contact email +
 * jurisdiction + display name. Per-role specifics (which games a
 * mod scopes to, which slug a publisher claims) come later in the
 * approval flow — we don't ask for them here so the form stays the
 * same shape regardless of which role the applicant picks.
 */
@Component({
  selector: 'app-producer-registration-remote',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule],
  template: `
    <div class="reg-page">
      <div class="reg-card">
        <h1>Apply for <span>Producer / Mod</span> authority</h1>
        <p class="subtitle">Fill this out and an admin will review.
          You'll keep your current account access either way —
          approval just unlocks the per-game producer / mod
          dashboards.</p>

        @if (submitted()) {
          <p class="success">Your application is on file. Watch the
            inbox you provided for a follow-up from a Snapdragon
            admin.</p>
        } @else {
          <form [formGroup]="form" (ngSubmit)="submit()">
            <label>
              Display name
              <input formControlName="displayName" type="text"
                     placeholder="how mods + publishers see you" />
            </label>
            <label>
              Contact email
              <input formControlName="contactEmail" type="email"
                     placeholder="for disputes + moderation messages" />
            </label>
            <label>
              Jurisdiction
              <input formControlName="jurisdiction" type="text"
                     placeholder="country / state for compliance routing" />
            </label>
            <label>
              Role
              <select formControlName="requestedRole">
                <option value="producer">Producer (publish games)</option>
                <option value="mod">Mod (review sessions in a game)</option>
              </select>
            </label>
            <label class="textarea">
              Why?
              <textarea formControlName="rationale"
                        rows="4"
                        placeholder="One short paragraph the admin will read on review."></textarea>
            </label>
            @if (errorMessage()) {
              <p class="error">{{ errorMessage() }}</p>
            }
            <button type="submit"
                    [disabled]="form.invalid || sending()"
                    class="submit-btn">
              {{ sending() ? 'Submitting…' : 'Submit application' }}
            </button>
          </form>
        }
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; background: #0a0a12; }
    .reg-page {
      min-height: 100vh; display: flex; align-items: center;
      justify-content: center; padding: 24px;
      background-image: radial-gradient(
        ellipse at 50% 30%,
        rgba(124, 77, 255, 0.15) 0%, transparent 65%);
    }
    .reg-card {
      background: #12121e; border: 1px solid #1e1e32;
      border-radius: 16px; padding: 40px; width: 100%;
      max-width: 480px; color: #e8e8f0;
      box-shadow: 0 24px 64px #0009, 0 0 0 1px #7c4dff1a;
    }
    h1 { font-size: 1.6rem; font-weight: 700; margin: 0 0 12px;
         text-align: center; }
    h1 span { background: linear-gradient(135deg,#7c4dff,#b388ff);
              -webkit-background-clip: text;
              -webkit-text-fill-color: transparent; }
    .subtitle { color: #a8acc4; font-size: 0.95rem; line-height: 1.5;
                margin: 0 0 24px; text-align: center; }
    form { display: flex; flex-direction: column; gap: 14px; }
    label { display: flex; flex-direction: column; gap: 4px;
            font-size: 0.85rem; color: #aab1c1; }
    input, select, textarea {
      background: #ffffff0a; border: 1px solid #1e1e32;
      border-radius: 8px; padding: 10px 14px; color: #e8e8f0;
      font: inherit;
    }
    input:focus, select:focus, textarea:focus {
      border-color: #7c4dff; outline: none;
      box-shadow: 0 0 0 2px #7c4dff33;
    }
    .submit-btn {
      background: #7c4dff; color: #fff; border: none;
      border-radius: 8px; padding: 12px 22px; font-weight: 600;
      cursor: pointer; transition: background .2s;
    }
    .submit-btn:hover:not(:disabled) { background: #8b5cff; }
    .submit-btn:disabled { opacity: .6; cursor: not-allowed; }
    .success { color: #4caf50; font-weight: 600; text-align: center; }
    .error   { color: #ff6b6b; }
  `],
})
export class ProducerRegistrationRemoteComponent {

  private http = inject(HttpClient);

  readonly form = new FormGroup({
    displayName:   new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    contactEmail:  new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.email] }),
    jurisdiction:  new FormControl('', { nonNullable: true, validators: [Validators.required] }),
    requestedRole: new FormControl<'producer' | 'mod'>('producer', { nonNullable: true }),
    rationale:     new FormControl('', { nonNullable: true, validators: [Validators.required, Validators.minLength(20)] }),
  });

  readonly sending = signal(false);
  readonly submitted = signal(false);
  readonly errorMessage = signal('');

  submit(): void {
    if (this.form.invalid) return;
    this.sending.set(true);
    this.errorMessage.set('');
    this.http
      .post('/api/producer-contacts', this.form.getRawValue())
      .subscribe({
        next: () => {
          this.submitted.set(true);
          this.sending.set(false);
        },
        error: (err) => {
          this.errorMessage.set(
            err?.error?.message ?? `Submission failed: ${err?.status ?? 'unknown'}`);
          this.sending.set(false);
        },
      });
  }
}
