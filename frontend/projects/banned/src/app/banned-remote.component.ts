import { Component, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ActivatedRoute } from '@angular/router';

/**
 * Federated remote rendered to revoked / blocked users — the ONE
 * route they can reach besides /login + /registration. Funnelled
 * here by the host's auth-error interceptor when a request returns
 * 403 + an {@code X-Revoked} header (the BFF's
 * {@code RevocationListService} stamps that header on 403s).
 *
 * <p>Contains a pre-filled email template the user can paste to a
 * producer / mod contact address so a real human can review the
 * revocation. The recipient address is the producer-contacts row
 * the producer registration flow registers (decision item #8 in
 * the platform roadmap); for now we surface the default
 * support@snapdragon.games until that schema lands.
 *
 * <p>Anonymous-allowed by SecurityConfig — the banned user might
 * already have their token revoked at the network layer; they
 * shouldn't be locked out of the appeal channel because of it.
 */
@Component({
  selector: 'app-banned-remote',
  standalone: true,
  imports: [CommonModule],
  template: `
    <div class="banned-page">
      <div class="banned-card">
        <div class="badge">⊘</div>
        <h1>Account suspended</h1>
        <p class="lead">Your access to this Snapdragon stack has been
          temporarily revoked. The platform won't let you reach any
          other route until a producer / mod reviews the suspension.</p>

        <p class="meta">
          @if (revocationReason()) {
            <strong>Reason on file:</strong> {{ revocationReason() }}
          }
          @if (revocationKey()) {
            <br><strong>Revocation key:</strong>
            <code>{{ revocationKey() }}</code>
          }
        </p>

        <h2>Appeal this</h2>
        <p>Copy the boilerplate below + email a producer. Include the
          revocation key so they can find the record.</p>

        <pre class="email-template">{{ emailBody() }}</pre>

        <a class="mailto-btn"
           [href]="mailtoHref()"
           target="_blank"
           rel="noopener noreferrer">
          ✉ Send email to producers
        </a>

        <p class="back">
          <a routerLink="/login">← Back to sign in</a>
        </p>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; background: #0a0a12; }
    .banned-page {
      min-height: 100vh; display: flex; align-items: center;
      justify-content: center; padding: 24px;
      background-image: radial-gradient(
        ellipse at 50% 30%,
        rgba(255, 107, 107, 0.12) 0%,
        transparent 65%);
    }
    .banned-card {
      background: #12121e; border: 1px solid #1e1e32;
      border-radius: 16px; padding: 40px; width: 100%;
      max-width: 520px; color: #e8e8f0;
      box-shadow: 0 24px 64px #0009, 0 0 0 1px #e0524d22;
    }
    .badge {
      font-size: 3rem; color: #ff6b6b; text-align: center;
      margin-bottom: 12px;
    }
    h1 { font-size: 1.6rem; font-weight: 700; margin: 0 0 12px;
         text-align: center; }
    h2 { font-size: 1.1rem; margin: 28px 0 12px; }
    .lead, p { color: #7070a0; font-size: 0.95rem; line-height: 1.5; margin: 0 0 12px; }
    .meta { background: #1a1d28; padding: 12px 16px;
            border-radius: 8px; font-size: 0.85rem; color: #aab1c1; }
    .meta code { background: #ffffff0d; padding: 2px 6px; border-radius: 4px; }
    .email-template {
      background: #ffffff08; border: 1px solid #1e1e32;
      border-radius: 8px; padding: 14px 16px;
      font-family: ui-monospace, monospace;
      font-size: 0.82rem; color: #e8e8f0; line-height: 1.45;
      white-space: pre-wrap; word-break: break-word;
      max-height: 240px; overflow-y: auto;
    }
    .mailto-btn {
      display: inline-flex; align-items: center; justify-content: center;
      gap: 8px; background: #7c4dff; color: #fff;
      text-decoration: none; border-radius: 8px;
      padding: 12px 26px; font-weight: 600; margin-top: 16px;
      transition: background .2s;
    }
    .mailto-btn:hover { background: #8b5cff; }
    .back { text-align: center; margin-top: 24px; font-size: 0.85rem; }
    .back a { color: #7070a0; text-decoration: none; }
    .back a:hover { color: #b388ff; }
  `],
})
export class BannedRemoteComponent {

  private route = inject(ActivatedRoute);

  // The auth-error interceptor navigates to `/banned` with the
  // revocation reason + key in the query string. If those aren't
  // present (a user who landed directly here) the page still
  // renders the appeal flow with empty meta.
  readonly revocationReason = signal<string>(
      this.route.snapshot.queryParamMap.get('reason') ?? '');
  readonly revocationKey    = signal<string>(
      this.route.snapshot.queryParamMap.get('key') ?? '');

  readonly emailBody = signal<string>(this.buildEmailBody());

  private buildEmailBody(): string {
    const key    = this.route.snapshot.queryParamMap.get('key') ?? '<unknown>';
    const reason = this.route.snapshot.queryParamMap.get('reason') ?? '<no reason on file>';
    return [
      'Subject: Appeal — account suspension on Snapdragon Games',
      '',
      'Hello,',
      '',
      'My account has been suspended on the Snapdragon Games platform.',
      'I would like to appeal this suspension.',
      '',
      `Revocation key: ${key}`,
      `Reason on file: ${reason}`,
      '',
      'Context (please complete before sending):',
      '  • What I was doing when the suspension landed:',
      '  • Any prior interaction with mods / producers:',
      '  • Why I believe this should be reviewed:',
      '',
      'Thanks,',
    ].join('\n');
  }

  mailtoHref(): string {
    const subject = encodeURIComponent('Appeal — account suspension on Snapdragon Games');
    const body    = encodeURIComponent(this.emailBody());
    // TODO: replace with the producer-contacts row resolved at
    // build time once that schema (roadmap item #8) lands.
    return `mailto:support@snapdragon.games?subject=${subject}&body=${body}`;
  }
}
