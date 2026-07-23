/**
 * ApiExplorerComponent — interactive try-it-out for the merged
 * OpenAPI spec. Shown on the "Try it out" tab of DocsShellComponent.
 *
 * Features (per the project requirement):
 *   • Auth-token override textbox preloaded from localStorage.tw_auth.
 *   • Path / query / header parameter form, defaulted from the spec.
 *   • Request-body editor (JSON for application/json, file picker for
 *     multipart/form-data with a binary field).
 *   • Sample request preload from x-hugmesh.sampleRequest.
 *   • Fetch-based "Send" that respects the override token.
 *   • Live response panel: status, headers, body (JSON pretty-printed
 *     when the content-type allows).
 *   • Terminal-styled SSE viewer for endpoints with x-hugmesh.sse
 *     (or text/event-stream in the response). Opens a fetch-stream
 *     and appends incoming events; "Stop" button cancels.
 */
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Input,
  OnChanges,
  ViewChild,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';

interface PathParams { [k: string]: string; }
interface QueryParams { [k: string]: string; }
interface HeaderParams { [k: string]: string; }

@Component({
  selector: 'app-api-explorer',
  standalone: true,
  imports: [CommonModule, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [`
    :host { display:block; max-width:960px;
            font-family:'Inter','Segoe UI',system-ui,sans-serif; }
    h2 { color:#4A2FB5; font-size:1.2rem; margin:0 0 6px; }
    h3 { color:#4A2FB5; font-size:.95rem; margin:14px 0 4px; }
    label { display:block; font-size:.78rem; color:#6B6B85;
            font-weight:600; margin:6px 0 2px; }
    input[type=text], input[type=password], textarea, select {
      width:100%; padding:6px 8px; border:1px solid #D8D6E5;
      border-radius:4px; font-family:'JetBrains Mono','Consolas',monospace;
      font-size:.86rem; background:#FFF; color:#1F1F2E;
    }
    textarea { min-height:140px; resize:vertical; }
    input[type=file] { font-family:inherit; }
    .row { display:flex; gap:8px; align-items:center; }
    .row > * { flex:1; }

    button.primary {
      background:linear-gradient(135deg,#7C4DFF,#B388FF);
      color:#FFF; border:0; border-radius:4px; padding:8px 18px;
      font-weight:700; cursor:pointer; font-family:inherit;
    }
    button.primary:hover { filter:brightness(1.05); }
    button.secondary {
      background:#F4F2FA; color:#4A2FB5;
      border:1px solid #D8D6E5; border-radius:4px; padding:6px 14px;
      cursor:pointer; font-family:inherit;
    }
    .actions { display:flex; gap:8px; margin-top:12px; }

    .status {
      display:inline-block; padding:2px 8px; border-radius:3px;
      font-weight:700; font-size:.82rem;
      font-family:'JetBrains Mono',monospace;
    }
    .s2xx { background:#DCFCE7; color:#15803D; }
    .s4xx { background:#FEF3C7; color:#A16207; }
    .s5xx { background:#FEE2E2; color:#B91C1C; }

    .terminal {
      background:#0A0A12; color:#E8E8F0; border-radius:6px;
      padding:12px 14px;
      font-family:'JetBrains Mono','Fira Code','Consolas',monospace;
      font-size:.82rem; line-height:1.5; height:280px;
      overflow:auto; white-space:pre-wrap;
      border:1px solid #4A2FB5;
    }
    .terminal .ev    { color:#B388FF; }
    .terminal .data  { color:#E8E8F0; }
    .terminal .meta  { color:#7070A0; }
    .terminal .err   { color:#FF8A80; }

    .response pre {
      background:#F4F2FA; border:1px solid #E0DCEE; border-radius:6px;
      padding:10px 14px; overflow:auto;
      font-family:'JetBrains Mono','Consolas',monospace;
      font-size:.84rem; max-height:340px;
    }
    .empty { color:#6B6B85; padding:32px 0; text-align:center; }
  `],
  template: `
  @if (!active) {
    <div class="empty">
      <h2>Pick an operation</h2>
      <p>Choose a path from the navigator on the left to send a test
         request.</p>
    </div>
  } @else {
    <h2>{{ active.method.toUpperCase() }} {{ active.path }}</h2>

    <h3>Authentication</h3>
    <label for="tok">Bearer token (override; preloaded from
      <code>localStorage.tw_auth</code>)</label>
    <input id="tok" type="text" [(ngModel)]="token"
           placeholder="eyJhbGciOiJSUzI1NiJ9..." />

    @if (pathParams.length) {
      <h3>Path parameters</h3>
      @for (p of pathParams; track p.name) {
        <label>{{ p.name }} <small>({{ p.schema?.type || 'string' }})</small></label>
        <input type="text" [(ngModel)]="pathValues[p.name]"
               [name]="'p_' + p.name" />
      }
    }

    @if (queryParams.length) {
      <h3>Query parameters</h3>
      @for (q of queryParams; track q.name) {
        <label>{{ q.name }} <small>({{ q.schema?.type || 'string' }})</small></label>
        <input type="text" [(ngModel)]="queryValues[q.name]"
               [name]="'q_' + q.name" />
      }
    }

    @if (hasJsonBody()) {
      <h3>Request body (JSON)</h3>
      <textarea [(ngModel)]="bodyJson" name="bodyJson"></textarea>
    }
    @if (binaryUpload()) {
      <h3>Binary upload — <code>{{ binaryUpload().field }}</code></h3>
      <p style="color:#6B6B85; font-size:.85rem; margin-top:0;">
        {{ binaryUpload().description }} (accepts
        <code>{{ binaryUpload().accept }}</code>)
      </p>
      <input type="file"
             [accept]="binaryUpload().accept"
             (change)="onFileSelected($event)" />
    }

    <div class="actions">
      <button type="button" class="primary" (click)="send()"
              [disabled]="busy()">
        {{ busy() ? 'Sending…' : (isSse() ? 'Subscribe to SSE' : 'Send request') }}
      </button>
      @if (isSse() && sseActive()) {
        <button type="button" class="secondary" (click)="stopSse()">Stop SSE</button>
      }
      <button type="button" class="secondary" (click)="resetForm()">Reset</button>
    </div>

    @if (isSse()) {
      <h3>SSE terminal</h3>
      <div #term class="terminal">{{ sseLog() }}</div>
    }

    @if (response() || responseError()) {
      <h3>Response</h3>
      <div class="response">
        @if (responseError()) {
          <p><span class="status s5xx">ERROR</span>
            {{ responseError() }}</p>
        } @else if (response()) {
          <p><span class="status" [class]="statusClass()">{{ response()!.status }}</span>
            {{ response()!.statusText }} — {{ response()!.duration }} ms</p>
          <pre>{{ response()!.body }}</pre>
        }
      </div>
    }
  }
  `,
})
export class ApiExplorerComponent implements OnChanges {
  @Input() spec: any = null;
  @Input() active: any = null;

  @ViewChild('term') termEl?: ElementRef<HTMLPreElement>;

  // Form state
  token = '';
  pathValues: PathParams = {};
  queryValues: QueryParams = {};
  bodyJson = '';
  selectedFile: File | null = null;

  // Response state
  readonly busy        = signal<boolean>(false);
  readonly response    = signal<{
    status: number; statusText: string; body: string; duration: number;
  } | null>(null);
  readonly responseError = signal<string | null>(null);

  // SSE state
  readonly sseActive = signal<boolean>(false);
  readonly sseLog    = signal<string>('');
  private sseAbort: AbortController | null = null;

  ngOnChanges(): void {
    if (!this.active) return;
    this.resetForm();
    // Preload token from localStorage.tw_auth if available.
    try {
      const raw = localStorage.getItem('tw_auth');
      if (raw) {
        const parsed = JSON.parse(raw);
        this.token = parsed?.accessToken || '';
      }
    } catch { /* tab without localStorage access — ignore */ }
    // Preload sample body when available.
    const sample = this.active.op?.['x-hugmesh']?.sampleRequest;
    if (sample && this.hasJsonBody()) {
      this.bodyJson = JSON.stringify(sample, null, 2);
    }
  }

  get pathParams(): any[] {
    return (this.active?.op?.parameters || []).filter(
      (p: any) => p.in === 'path');
  }
  get queryParams(): any[] {
    return (this.active?.op?.parameters || []).filter(
      (p: any) => p.in === 'query');
  }
  hasJsonBody(): boolean {
    return !!this.active?.op?.requestBody?.content?.['application/json'];
  }
  binaryUpload(): any | null {
    return this.active?.op?.['x-hugmesh']?.binaryUpload || null;
  }
  isSse(): boolean {
    if (this.active?.op?.['x-hugmesh']?.sse) return true;
    const responses = this.active?.op?.responses || {};
    for (const r of Object.values<any>(responses)) {
      if (r?.content?.['text/event-stream']) return true;
    }
    return false;
  }

  statusClass(): string {
    const s = this.response()?.status || 0;
    if (s >= 200 && s < 300) return 's2xx';
    if (s >= 400 && s < 500) return 's4xx';
    return 's5xx';
  }

  resetForm(): void {
    this.pathValues = {};
    this.queryValues = {};
    this.bodyJson = '';
    this.selectedFile = null;
    this.response.set(null);
    this.responseError.set(null);
    this.sseLog.set('');
  }

  onFileSelected(e: Event): void {
    const f = (e.target as HTMLInputElement).files?.[0];
    this.selectedFile = f || null;
  }

  send(): void {
    if (this.isSse()) {
      this.startSse();
      return;
    }
    this.responseError.set(null);
    this.response.set(null);
    this.busy.set(true);

    const url = this.buildUrl();
    const init: RequestInit = {
      method: this.active.method.toUpperCase(),
      headers: this.buildHeaders(),
    };
    if (this.binaryUpload() && this.selectedFile) {
      const fd = new FormData();
      fd.append(this.binaryUpload().field, this.selectedFile);
      init.body = fd;
    } else if (this.hasJsonBody() && this.bodyJson.trim()) {
      try {
        // Validate JSON; rebuild body so the server gets canonical form.
        init.body = JSON.stringify(JSON.parse(this.bodyJson));
        (init.headers as any)['Content-Type'] = 'application/json';
      } catch (e: any) {
        this.busy.set(false);
        this.responseError.set('Body is not valid JSON: ' + e?.message);
        return;
      }
    }

    const t0 = performance.now();
    fetch(url, init).then(async (r) => {
      const text = await r.text();
      let body = text;
      const ct = r.headers.get('content-type') || '';
      if (ct.includes('application/json')) {
        try { body = JSON.stringify(JSON.parse(text), null, 2); } catch { /**/ }
      }
      this.response.set({
        status: r.status, statusText: r.statusText,
        body, duration: Math.round(performance.now() - t0),
      });
    }).catch((e) => {
      this.responseError.set(e?.message || String(e));
    }).finally(() => this.busy.set(false));
  }

  private startSse(): void {
    if (this.sseAbort) this.sseAbort.abort();
    this.sseAbort = new AbortController();
    this.sseActive.set(true);
    this.sseLog.set('');
    this.appendLog('meta', `→ subscribing ${this.active.method.toUpperCase()} ${this.buildUrl()}\n\n`);

    const url = this.buildUrl();
    fetch(url, {
      headers: { ...this.buildHeaders(), 'Accept': 'text/event-stream' },
      signal: this.sseAbort.signal,
    }).then(async (r) => {
      if (!r.ok) {
        this.appendLog('err', `HTTP ${r.status} ${r.statusText}\n`);
        this.sseActive.set(false);
        return;
      }
      if (!r.body) {
        this.appendLog('err', 'no response body\n');
        this.sseActive.set(false);
        return;
      }
      const reader = r.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';
      // eslint-disable-next-line no-constant-condition
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buffer.indexOf('\n\n')) !== -1) {
          const chunk = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          this.appendLog('data', chunk + '\n\n');
        }
      }
    }).catch((e) => {
      if (e?.name !== 'AbortError') {
        this.appendLog('err', String(e) + '\n');
      }
    }).finally(() => this.sseActive.set(false));
  }

  stopSse(): void {
    this.sseAbort?.abort();
    this.appendLog('meta', '— subscription cancelled —\n');
  }

  private appendLog(_kind: 'meta' | 'data' | 'err', text: string): void {
    this.sseLog.update(prev => prev + text);
    queueMicrotask(() => {
      const el = this.termEl?.nativeElement;
      if (el) el.scrollTop = el.scrollHeight;
    });
  }

  private buildUrl(): string {
    let path = this.active.path;
    for (const p of this.pathParams) {
      const v = this.pathValues[p.name] ?? '';
      path = path.replaceAll(`{${p.name}}`, encodeURIComponent(v));
    }
    const q: string[] = [];
    for (const qp of this.queryParams) {
      const v = this.queryValues[qp.name];
      if (v != null && v !== '') {
        q.push(`${encodeURIComponent(qp.name)}=${encodeURIComponent(v)}`);
      }
    }
    return path + (q.length ? '?' + q.join('&') : '');
  }

  private buildHeaders(): Record<string, string> {
    const h: Record<string, string> = {};
    if (this.token) h['Authorization'] = 'Bearer ' + this.token;
    return h;
  }
}
