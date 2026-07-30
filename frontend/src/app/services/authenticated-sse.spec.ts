import { openAuthenticatedSse } from './authenticated-sse';

/** Build a fetch stub whose response body streams `chunks` then ends. */
function streamingFetch(chunks: string[], status = 200): {
  impl: typeof fetch;
  calls: { url: string; headers: Record<string, string> }[];
} {
  const calls: { url: string; headers: Record<string, string> }[] = [];
  const impl = (async (url: string, init?: RequestInit) => {
    calls.push({ url: String(url), headers: (init?.headers ?? {}) as Record<string, string> });
    const encoder = new TextEncoder();
    let i = 0;
    const body = {
      getReader: () => ({
        read: async () =>
          i < chunks.length
            ? { done: false, value: encoder.encode(chunks[i++]) }
            : { done: true, value: undefined },
      }),
    };
    return { ok: status >= 200 && status < 300, status, body } as unknown as Response;
  }) as unknown as typeof fetch;
  return { impl, calls };
}

describe('openAuthenticatedSse', () => {
  it('sends the bearer token EventSource could never send', async () => {
    const { impl, calls } = streamingFetch(['event: movement\ndata: {"a":1}\n\n']);
    const events: Array<[string, string]> = [];
    const sse = openAuthenticatedSse('/api/game/stream?sessionId=s1', {
      token: 'tok-123',
      channels: ['movement'],
      onEvent: (c, d) => events.push([c, d]),
      fetchImpl: impl,
    });
    await new Promise(r => setTimeout(r, 0));
    sse.close();

    expect(calls.length).toBe(1);
    expect(calls[0].url).toContain('sessionId=s1');
    expect(calls[0].headers['Authorization']).toBe('Bearer tok-123');
    expect(events).toEqual([['movement', '{"a":1}']]);
  });

  it('parses events split across chunk boundaries', async () => {
    // The blank-line terminator straddles two reads — the naive
    // "one chunk == one event" reader drops this.
    const { impl } = streamingFetch(['event: world\ndata: {"b', '":2}\n', '\nevent: movement\ndata: 7\n\n']);
    const events: Array<[string, string]> = [];
    const sse = openAuthenticatedSse('/api/game/stream', {
      channels: ['world', 'movement'],
      onEvent: (c, d) => events.push([c, d]),
      fetchImpl: impl,
    });
    await new Promise(r => setTimeout(r, 0));
    sse.close();

    expect(events).toEqual([['world', '{"b":2}'], ['movement', '7']]);
  });

  it('ignores keepalive comments and unwanted channels', async () => {
    const { impl } = streamingFetch([':keepalive\n\nevent: audio\ndata: x\n\nevent: movement\ndata: y\n\n']);
    const events: Array<[string, string]> = [];
    const sse = openAuthenticatedSse('/api/game/stream', {
      channels: ['movement'],
      onEvent: (c, d) => events.push([c, d]),
      fetchImpl: impl,
    });
    await new Promise(r => setTimeout(r, 0));
    sse.close();

    expect(events).toEqual([['movement', 'y']]);
  });

  it('reports an HTTP rejection instead of failing silently', async () => {
    // The 401 that broke multiplayer: EventSource surfaced it as an opaque
    // error event and retried forever. Here it reaches onError.
    const { impl } = streamingFetch([], 401);
    let err: unknown = null;
    const sse = openAuthenticatedSse('/api/game/stream?sessionId=s1', {
      token: null,
      channels: ['movement'],
      onEvent: () => {},
      onError: e => { err = e; },
      retryMs: 10_000,
      fetchImpl: impl,
    });
    await new Promise(r => setTimeout(r, 0));
    sse.close();

    expect(String(err)).toContain('401');
  });

  it('close() stops further reconnects', async () => {
    const { impl, calls } = streamingFetch([], 500);
    const sse = openAuthenticatedSse('/api/game/stream', {
      channels: ['movement'],
      onEvent: () => {},
      retryMs: 1,
      fetchImpl: impl,
    });
    await new Promise(r => setTimeout(r, 0));
    sse.close();
    const seen = calls.length;
    await new Promise(r => setTimeout(r, 20));

    expect(calls.length).toBe(seen);
  });
});
