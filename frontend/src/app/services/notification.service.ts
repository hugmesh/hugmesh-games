import { Injectable, signal } from '@angular/core';

/**
 * One queued notification. {@code kind} drives the colour/icon; the
 * {@code timeoutMs} default is 4 seconds for info/success and 8 for
 * warning/error so the user has time to read the message.
 */
export interface AppNotification {
  id: number;
  kind: 'info' | 'success' | 'warning' | 'error';
  message: string;
  detail?: string;
  timeoutMs: number;
}

/**
 * Tiny in-memory toast queue. The header (`AppComponent`) renders the
 * stack — every component emits via {@code show*}, no template-side
 * error dialogs.
 *
 * <p>Kept deliberately minimal: no third-party toast library. The
 * point is uniform messaging so we never surface raw HTTP status text
 * ("status 500") to a player who has no model for it.
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private nextId = 1;
  readonly notifications = signal<AppNotification[]>([]);

  info(message: string, detail?: string)    { return this.push('info',    message, detail, 4000); }
  success(message: string, detail?: string) { return this.push('success', message, detail, 4000); }
  warn(message: string, detail?: string)    { return this.push('warning', message, detail, 8000); }
  error(message: string, detail?: string)   { return this.push('error',   message, detail, 8000); }

  dismiss(id: number): void {
    this.notifications.update(list => list.filter(n => n.id !== id));
  }

  clear(): void { this.notifications.set([]); }

  private push(kind: AppNotification['kind'], message: string, detail: string | undefined, timeoutMs: number): number {
    const id = this.nextId++;
    const note: AppNotification = { id, kind, message, detail, timeoutMs };
    this.notifications.update(list => [...list, note]);
    if (timeoutMs > 0) {
      setTimeout(() => this.dismiss(id), timeoutMs);
    }
    return id;
  }
}
