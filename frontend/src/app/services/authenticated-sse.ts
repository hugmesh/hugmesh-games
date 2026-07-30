/**
 * Authenticated Server-Sent Events.
 *
 * The browser `EventSource` API cannot send an `Authorization` header, and
 * this app is native-login (the JWT lives in `localStorage`, there is no
 * session cookie). Every JWT-protected SSE channel opened with a raw
 * `EventSource` is therefore ANONYMOUS and gets a 401 — silently, because
 * `EventSource` surfaces an auth rejection as a generic `error` event and
 * then retries forever.
 *
 * That is exactly what broke session-scoped multiplayer: the BFF gates
 * `/api/game/stream?sessionId=…` on session membership (a deliberate BOLA
 * fix), so the guest's subscription 401'd and no player ever saw another
 * player move.
 *
 * `openAuthenticatedSse` uses `fetch` — which DOES carry headers — and
 * parses the SSE wire format off the streaming response body. It keeps the
 * one behaviour callers depend on from `EventSource`: automatic reconnect
 * with a backoff, cancellable via the returned handle.
 *
 * Wire format (only what the BFF emits): `event: <name>`, `data: <payload>`
 * (repeatable — joined with newlines), `id:`/`retry:` ignored, a blank line
 * terminates the event, and a leading `:` is a comment (the keepalive).
 */

/** Handle mirroring the slice of `EventSource` callers actually use. */
export interface AuthenticatedSse {
  /** Stop reading and cancel any pending reconnect. Idempotent. */
  close(): void;
}

export interface AuthenticatedSseOptions {
  /** Bearer token; when absent the request goes out unauthenticated. */
  token?: string | null;
  /** Called for every event whose name is in `channels`. */
  onEvent: (channel: string, data: string) => void;
  /** Event names to surface. Others (and comments) are ignored. */
  channels: readonly string[];
  /** Called on transport/HTTP failure, before the reconnect delay. */
  onError?: (err: unknown) => void;
  /** Reconnect delay in ms (default 3000). */
  retryMs?: number;
  /** Injectable for tests. Defaults to the global `fetch`. */
  fetchImpl?: typeof fetch;
}

export function openAuthenticatedSse(
  url: string,
  opts: AuthenticatedSseOptions,
): AuthenticatedSse {
  const doFetch = opts.fetchImpl ?? ((...a: Parameters<typeof fetch>) => fetch(...a));
  const wanted = new Set(opts.channels);
  const retryMs = opts.retryMs ?? 3000;
  let closed = false;
  let controller: AbortController | null = null;
  let retryTimer: ReturnType<typeof setTimeout> | null = null;

  const dispatch = (block: string): void => {
    // A comment-only block (`:keepalive`) carries no event.
    let name = 'message';
    const data: string[] = [];
    for (const rawLine of block.split('\n')) {
      const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
      if (!line || line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon === -1 ? line : line.slice(0, colon);
      // Exactly one optional leading space after the colon is stripped.
      let value = colon === -1 ? '' : line.slice(colon + 1);
      if (value.startsWith(' ')) value = value.slice(1);
      if (field === 'event') name = value;
      else if (field === 'data') data.push(value);
    }
    if (data.length && wanted.has(name)) opts.onEvent(name, data.join('\n'));
  };

  const scheduleRetry = (): void => {
    if (closed || retryTimer) return;
    retryTimer = setTimeout(() => {
      retryTimer = null;
      void run();
    }, retryMs);
  };

  const run = async (): Promise<void> => {
    if (closed) return;
    controller = new AbortController();
    try {
      const headers: Record<string, string> = { Accept: 'text/event-stream' };
      if (opts.token) headers['Authorization'] = `Bearer ${opts.token}`;
      const res = await doFetch(url, {
        headers,
        signal: controller.signal,
        // Send cookies too, so a cookie-authenticated deployment keeps working.
        credentials: 'same-origin',
      });
      if (!res.ok || !res.body) {
        throw new Error(`SSE ${url} failed: HTTP ${res.status}`);
      }
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        // Events are separated by a blank line (\n\n, or \r\n\r\n).
        for (;;) {
          const sep = /\r?\n\r?\n/.exec(buffer);
          if (!sep) break;
          const block = buffer.slice(0, sep.index);
          buffer = buffer.slice(sep.index + sep[0].length);
          dispatch(block);
        }
      }
      // Server closed the stream — reconnect like EventSource would.
      if (!closed) scheduleRetry();
    } catch (err) {
      if (closed || (err as { name?: string })?.name === 'AbortError') return;
      opts.onError?.(err);
      scheduleRetry();
    }
  };

  void run();

  return {
    close(): void {
      closed = true;
      if (retryTimer) { clearTimeout(retryTimer); retryTimer = null; }
      controller?.abort();
      controller = null;
    },
  };
}
