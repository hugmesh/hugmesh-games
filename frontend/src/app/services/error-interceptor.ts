import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { NotificationService } from './notification.service';

/**
 * Translates HTTP failures into user-friendly toasts.
 *
 * <p>Players never see "status 500" or a raw stack trace. Instead the
 * interceptor maps the status code (and optional server-supplied
 * {@code message}) into an action-oriented sentence:
 *
 * <pre>
 *   401 → "Please sign in again."
 *   403 → "You don't have permission for that."
 *   404 → "We couldn't find what you were looking for."
 *   409 → "That conflicts with someone else's action — try again."
 *   500 → "Something went wrong on our side — try again in a moment."
 * </pre>
 *
 * <p>The original error is re-thrown so component subscribers can
 * still react if they need to — the interceptor only adds the toast.
 *
 * <p>One exception: GETs to background-polling endpoints (anything
 * tagged with {@code X-Notify: silent} OR matching the SSE / heartbeat
 * URL allow-list) skip the toast so a transient blip doesn't bury the
 * UI.
 */
export const errorInterceptor: HttpInterceptorFn = (req, next) => {
  const notify = inject(NotificationService);
  const isSilent = req.headers.get('X-Notify') === 'silent'
    || req.url.includes('/api/admin/stream')
    || req.url.includes('/api/game/stream')
    || req.url.includes('/heartbeat');

  return next(req).pipe(
    catchError((err: unknown) => {
      if (!isSilent && err instanceof HttpErrorResponse) {
        const { message, detail } = friendlyMessage(err);
        notify.error(message, detail);
      }
      return throwError(() => err);
    }),
  );
};

interface FriendlyError { message: string; detail?: string; }

function friendlyMessage(err: HttpErrorResponse): FriendlyError {
  const serverMessage = pickServerMessage(err);
  switch (err.status) {
    case 0:   return { message: 'Couldn\'t reach the server.', detail: 'Check your network connection.' };
    case 400: return { message: 'That request was invalid.', detail: serverMessage };
    case 401: return { message: 'Please sign in again.' };
    case 403: return { message: 'You don\'t have permission for that.' };
    case 404: return { message: 'We couldn\'t find what you were looking for.' };
    case 409: return { message: 'That conflicts with another action — try again.', detail: serverMessage };
    case 410: return { message: 'That session has already closed.' };
    case 429: return { message: 'You\'re going a bit fast — try again in a moment.' };
    case 500: return { message: 'Something went wrong on our side — try again in a moment.' };
    case 502:
    case 503:
    case 504: return { message: 'The server is busy — try again in a moment.' };
    default:
      return { message: 'Something went wrong.', detail: serverMessage };
  }
}

function pickServerMessage(err: HttpErrorResponse): string | undefined {
  const body = err.error;
  if (typeof body === 'string') return body;
  if (body && typeof body === 'object') {
    if (typeof body.message === 'string') return body.message;
    if (typeof body.error === 'string')   return body.error;
  }
  return undefined;
}
