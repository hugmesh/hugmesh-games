// e2e/games-arch/lib.mjs
//
// Zero-dependency transport helpers for the architecture e2e harness:
//   * ROPC token mint against the in-network Casdoor.
//   * Thin JSON fetch wrappers for the BFF.
//   * A manual SSE line-reader over fetch's ReadableStream body — this is the
//     load-bearing piece: a harness that only POSTs is worthless. It must
//     ACTUALLY read the server-sent event stream and surface per-channel
//     events so the runner can assert a relayed move was DELIVERED.
//
// Node 20+ only (global fetch + WHATWG streams). No npm install.

export const API_BASE = process.env.API_BASE || 'http://api:8080';
export const CASDOOR_BASE = process.env.CASDOOR_BASE || 'http://casdoor:8000';
export const WASM_FRAGMENT_BASE =
  process.env.WASM_FRAGMENT_BASE || 'http://wasm-fragment:8080';

const CLIENT_ID = process.env.CASDOOR_CLIENT_ID || 'twclient001';
const CLIENT_SECRET = process.env.CASDOOR_CLIENT_SECRET || '';

/**
 * Mint a JWT for a seed user via Casdoor's Resource-Owner-Password-Credentials
 * grant (the same in-network path the DAST mint-tokens.sh uses). Casdoor's
 * token endpoint accepts form-encoded grant_type=password.
 */
export async function mintToken(username, password) {
  const body = new URLSearchParams({
    grant_type: 'password',
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    username,
    password,
    scope: 'openid profile email',
  });
  const res = await fetch(`${CASDOOR_BASE}/api/login/oauth/access_token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    throw new Error(`token mint for ${username}: non-JSON response (${res.status}): ${text.slice(0, 200)}`);
  }
  if (!res.ok || !json.access_token) {
    throw new Error(
      `token mint for ${username} failed (${res.status}): ${json.error || ''} ${json.error_description || text.slice(0, 200)}`,
    );
  }
  return json.access_token;
}

/** JSON request to the BFF with a bearer token + optional X-Session-Id. */
export async function apiJson(method, path, { token, body, sessionId } = {}) {
  const headers = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;
  if (sessionId) headers['X-Session-Id'] = sessionId;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = null;
    }
  }
  return { status: res.status, ok: res.ok, json, text };
}

/** Plain GET, returns {status, ok, text, contentType, bytes}. */
export async function rawGet(url, { token } = {}) {
  const headers = {};
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(url, { headers });
  const buf = Buffer.from(await res.arrayBuffer());
  return {
    status: res.status,
    ok: res.ok,
    text: buf.toString('utf8'),
    contentType: res.headers.get('content-type') || '',
    bytes: buf.length,
  };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Open the multiplexed SSE stream for a session and collect typed events.
 *
 * Returns a handle:
 *   { events: [{channel, data}], received(channelPred): bool, close() }
 *
 * Implemented by reading the fetch ReadableStream body line-by-line and parsing
 * the SSE wire format (event:/data:/blank-line-terminated records). Mirrors the
 * direct transport in sse-host-client.js (_connectDirect): one addEventListener
 * per channel — here we just keep every (event, JSON.parse(data)) pair.
 */
export async function openSseStream(sessionId, { token } = {}) {
  const url = `${API_BASE}/api/game/stream?sessionId=${encodeURIComponent(sessionId)}`;
  const ac = new AbortController();
  const headers = { Accept: 'text/event-stream' };
  if (token) headers['Authorization'] = `Bearer ${token}`;

  const res = await fetch(url, { headers, signal: ac.signal });
  if (!res.ok || !res.body) {
    throw new Error(`SSE open for ${sessionId} failed: HTTP ${res.status}`);
  }

  const events = [];
  let open = true;

  // Background pump: decode the byte stream, split into SSE records, push
  // each {channel,data} onto `events`. A record is the lines between blank
  // lines; `event:` names the channel, `data:` carries the (JSON) payload.
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  const pump = (async () => {
    try {
      while (open) {
        const { value, done } = await reader.read();
        if (done) break;
        buf += decoder.decode(value, { stream: true });
        let idx;
        // Records are separated by a blank line (\n\n). Handle \r\n too.
        while ((idx = buf.search(/\r?\n\r?\n/)) !== -1) {
          const rec = buf.slice(0, idx);
          buf = buf.slice(idx + buf.match(/\r?\n\r?\n/)[0].length);
          let channel = null;
          const dataLines = [];
          for (const line of rec.split(/\r?\n/)) {
            if (line.startsWith(':')) continue; // comment / keepalive
            if (line.startsWith('event:')) channel = line.slice(6).trim();
            else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
          }
          if (channel && dataLines.length) {
            const raw = dataLines.join('\n');
            let data;
            try {
              data = JSON.parse(raw);
            } catch {
              data = raw;
            }
            events.push({ channel, data });
          }
        }
      }
    } catch (e) {
      if (e.name !== 'AbortError') {
        // Surface unexpected pump errors on the handle for the runner.
        events.push({ channel: '__error__', data: String(e && e.message) });
      }
    }
  })();

  return {
    events,
    /** Predicate-driven wait: resolves true once `pred(ev)` matches any event,
     *  or false after `timeoutMs`. Polls the growing `events` array. */
    async waitFor(pred, timeoutMs = 8000, pollMs = 50) {
      const deadline = Date.now() + timeoutMs;
      let i = 0;
      while (Date.now() < deadline) {
        for (; i < events.length; i++) {
          if (pred(events[i])) return events[i];
        }
        await sleep(pollMs);
      }
      // final sweep
      for (; i < events.length; i++) if (pred(events[i])) return events[i];
      return null;
    },
    /** True if ANY event already collected matches pred. */
    has(pred) {
      return events.some(pred);
    },
    async close() {
      open = false;
      try {
        ac.abort();
      } catch {
        /* noop */
      }
      try {
        await reader.cancel();
      } catch {
        /* noop */
      }
      await pump.catch(() => {});
    },
  };
}

/** Poll the BFF /actuator/health until UP or timeout. */
export async function waitForApiHealth(timeoutMs = 180000) {
  const deadline = Date.now() + timeoutMs;
  let last = '';
  while (Date.now() < deadline) {
    try {
      const r = await fetch(`${API_BASE}/actuator/health`);
      const t = await r.text();
      last = `HTTP ${r.status} ${t.slice(0, 120)}`;
      if (r.ok && /"status"\s*:\s*"UP"/.test(t)) return true;
    } catch (e) {
      last = String(e && e.message);
    }
    await sleep(2000);
  }
  throw new Error(`API health not UP within ${timeoutMs}ms (last: ${last})`);
}

export { sleep };
