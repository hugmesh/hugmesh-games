import { Component } from '@angular/core';
import { CommonModule } from '@angular/common';
import { SessionLogComponent } from './session-log.component';

/** Player-facing route at {@code /sessions/log}. Wraps the reusable
 *  SessionLogComponent in scope='me' so the player sees their own
 *  history. Admin / mod / publisher views embed the same
 *  SessionLogComponent with a different scope + casdoorId input. */
@Component({
  selector: 'app-session-log-page',
  standalone: true,
  imports: [CommonModule, SessionLogComponent],
  template: `
    <section class="session-log-page">
      <header class="session-log-page-header">
        <h2>Your session history</h2>
        <p>Every multiplayer session you've been part of, newest first.</p>
      </header>
      <app-session-log scope="me" [limit]="100"></app-session-log>
    </section>
  `,
  styles: [`
    .session-log-page { max-width: 1100px; margin: 24px auto; padding: 0 20px; color: #d8dde6; font-family: sans-serif; }
    .session-log-page-header h2 { margin: 0 0 4px; color: #f0f2fa; }
    .session-log-page-header p  { margin: 0 0 14px; color: #9aa3b8; font-size: 14px; }
  `],
})
export class SessionLogPageComponent {}
