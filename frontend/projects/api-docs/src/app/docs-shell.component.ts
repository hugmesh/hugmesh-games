/**
 * DocsShellComponent — top-level navigator for the api-docs remote.
 *
 * Layout (matches the api-navigator pattern from the host's left-rail
 * dashboards): a tag-grouped sidebar on the left, a per-operation
 * detail pane on the right. The user picks an operation from the
 * sidebar; the pane shows reference info AND a Try-it-out form AND
 * an SSE terminal panel when the operation streams.
 *
 * Mounted at /admin/docs in the host. Standalone — no Angular module
 * required. Consumed by federation as './DocsShell'.
 *
 * The merged OpenAPI spec is fetched at runtime from
 *   /docs/api/openapi.json
 * which the docs build script writes into the host's static asset
 * tree. When that file is missing the component falls back to
 * /v3/api-docs (raw spec, less rich).
 */
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';
import { HttpClient } from '@angular/common/http';
import { ApiExplorerComponent } from './api-explorer.component';
import { ApiReferenceComponent } from './api-reference.component';

interface OpenApiOperation {
  tag: string;
  method: string;
  path: string;
  op: any;
}

interface OpenApiSpec {
  openapi?: string;
  info?: any;
  tags?: { name: string; description?: string; 'x-snapdragon'?: any }[];
  paths?: Record<string, Record<string, any>>;
  components?: any;
}

const SPEC_PRIMARY = '/docs/api/openapi.json';
const SPEC_FALLBACK = '/v3/api-docs';

@Component({
  selector: 'app-docs-shell',
  standalone: true,
  imports: [CommonModule, ApiExplorerComponent, ApiReferenceComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [`
    :host {
      display:flex; height:100%; min-height:560px;
      font-family:'Inter','Segoe UI',system-ui,sans-serif;
      color:#1F1F2E; background:#FFF;
    }
    .nav {
      width:300px; flex:0 0 300px; border-right:1px solid #E0DCEE;
      overflow-y:auto; background:#F4F2FA;
    }
    .nav h2 {
      font-size:.78rem; letter-spacing:.08em; text-transform:uppercase;
      color:#7C4DFF; padding:14px 18px 4px; margin:0;
    }
    .nav .tag-group { padding:0 0 8px; }
    .nav .tag-name {
      display:flex; align-items:center; justify-content:space-between;
      padding:10px 18px 4px; font-weight:700; color:#4A2FB5;
      font-size:.92rem;
    }
    .nav button.op {
      display:flex; align-items:center; gap:8px;
      width:100%; text-align:left; padding:6px 18px;
      border:0; background:transparent; cursor:pointer; color:#1F1F2E;
      font-family:inherit; font-size:.86rem;
    }
    .nav button.op:hover { background:rgba(124,77,255,.08); }
    .nav button.op.active {
      background:rgba(124,77,255,.18); color:#4A2FB5; font-weight:600;
    }
    .nav .method {
      display:inline-block; min-width:48px; text-align:center;
      padding:1px 6px; border-radius:3px;
      font-size:.7rem; font-weight:700; letter-spacing:.04em;
    }
    .method-get    { background:#E0F2FE; color:#0369A1; }
    .method-post   { background:#DCFCE7; color:#15803D; }
    .method-put    { background:#FEF3C7; color:#A16207; }
    .method-delete { background:#FEE2E2; color:#B91C1C; }
    .method-patch  { background:#F3E8FF; color:#6B21A8; }

    .header {
      display:flex; align-items:center; justify-content:space-between;
      padding:16px 22px; border-bottom:1px solid #E0DCEE;
      background:#FFF;
    }
    .header h1 {
      margin:0; font-size:1.3rem; font-weight:800;
      background:linear-gradient(135deg,#7C4DFF,#B388FF);
      -webkit-background-clip:text; -webkit-text-fill-color:transparent;
      background-clip:text;
    }
    .header .meta { font-size:.78rem; color:#6B6B85; }
    .tabs { display:flex; gap:0; border-bottom:1px solid #E0DCEE;
            background:#FFF; padding:0 16px; }
    .tabs button {
      border:0; background:transparent; padding:10px 14px;
      font-family:inherit; font-size:.88rem; font-weight:600;
      color:#6B6B85; cursor:pointer; border-bottom:2px solid transparent;
    }
    .tabs button.active {
      color:#4A2FB5; border-bottom-color:#7C4DFF;
    }
    .pane {
      flex:1 1 auto; min-width:0; display:flex; flex-direction:column;
      overflow:hidden;
    }
    .pane > .body { flex:1 1 auto; overflow:auto; padding:20px 22px; }

    .empty {
      display:grid; place-items:center; text-align:center;
      padding:64px 20px; color:#6B6B85;
    }
    .empty h2 { color:#4A2FB5; margin:0 0 8px; }
    .err {
      background:#FEE2E2; color:#B91C1C; border-radius:6px;
      padding:14px 16px; font-size:.88rem; margin:14px 0;
    }
  `],
  template: `
  <aside class="nav">
    <h2>API navigator</h2>
    @for (tag of tags(); track tag.name) {
      <div class="tag-group">
        <div class="tag-name">{{ tag.name }}</div>
        @for (op of opsByTag(tag.name); track op.method + op.path) {
          <button
            type="button"
            class="op"
            [class.active]="active() === op"
            (click)="select(op)">
            <span class="method method-{{ op.method }}">{{ op.method.toUpperCase() }}</span>
            <span>{{ op.path }}</span>
          </button>
        }
      </div>
    }
    @if (tags().length === 0) {
      <div class="empty"><em>Loading spec…</em></div>
    }
  </aside>

  <div class="pane">
    <div class="header">
      <h1>{{ spec()?.info?.title || 'Snapdragon Gaming BFF' }}</h1>
      <span class="meta">v{{ spec()?.info?.version || '—' }} · {{ specSource() }}</span>
    </div>
    <div class="tabs">
      <button [class.active]="tab() === 'reference'" (click)="tab.set('reference')">Reference</button>
      <button [class.active]="tab() === 'explore'"   (click)="tab.set('explore')">Try it out</button>
      <button [class.active]="tab() === 'links'"     (click)="tab.set('links')">PDFs &amp; Javadoc</button>
    </div>
    @if (loadError()) {
      <div class="err">{{ loadError() }}</div>
    }
    <div class="body">
      @if (tab() === 'reference') {
        <app-api-reference [spec]="spec()" [active]="active()" />
      } @else if (tab() === 'explore') {
        <app-api-explorer [spec]="spec()" [active]="active()" />
      } @else if (tab() === 'links') {
        <h3>Reference PDFs</h3>
        <p>The architecture PDF and the six audience-specific user guides
           are produced by the same publishing pipeline that builds this
           navigator. They are not bundled with the federation image —
           the host serves them out of <code>/docs/refs/</code>.</p>
        <ul>
          <li><a href="/docs/refs/architecture.pdf" target="_blank">Architecture reference (PDF)</a></li>
          <li><a href="/docs/refs/intro-for-coders.pdf" target="_blank">Intro for coders</a></li>
          <li><a href="/docs/refs/administration.pdf" target="_blank">Administration manual</a></li>
          <li><a href="/docs/refs/godot-developer.pdf" target="_blank">Godot developer guide</a></li>
          <li><a href="/docs/refs/fullstack-developer.pdf" target="_blank">Full-stack developer guide</a></li>
          <li><a href="/docs/refs/maintainer-runbook.pdf" target="_blank">Maintainer runbook</a></li>
          <li><a href="/docs/refs/data-architect-overview.pdf" target="_blank">Data architect overview</a></li>
        </ul>
        <h3>Generated Javadoc</h3>
        <p>The Maven Javadoc HTML is themed with the same Snapdragon
           palette. It is regenerated by the docs build and served at
           <code>/docs/api/javadoc/</code>.</p>
        <p><a href="/docs/api/javadoc/index.html" target="_blank">Open Javadoc in a new tab</a></p>
      }
    </div>
  </div>
  `,
})
export class DocsShellComponent {
  private readonly http = inject(HttpClient);

  readonly spec = signal<OpenApiSpec | null>(null);
  readonly specSource = signal<string>('loading…');
  readonly loadError = signal<string | null>(null);
  readonly active = signal<OpenApiOperation | null>(null);
  readonly tab = signal<'reference' | 'explore' | 'links'>('reference');

  readonly operations = computed<OpenApiOperation[]>(() => {
    const s = this.spec();
    if (!s?.paths) return [];
    const rows: OpenApiOperation[] = [];
    for (const [path, ops] of Object.entries(s.paths)) {
      for (const [method, op] of Object.entries(ops || {})) {
        if (!['get', 'post', 'put', 'delete', 'patch'].includes(method)) continue;
        const tag = (op?.tags?.[0]) || 'Untagged';
        rows.push({ tag, method, path, op });
      }
    }
    rows.sort((a, b) =>
      a.tag.localeCompare(b.tag) ||
      a.path.localeCompare(b.path) ||
      a.method.localeCompare(b.method));
    return rows;
  });

  readonly tags = computed(() => {
    const seen = new Map<string, { name: string; desc?: string }>();
    for (const op of this.operations()) {
      if (!seen.has(op.tag)) seen.set(op.tag, { name: op.tag });
    }
    // Honour the spec's tag declaration order if present.
    const declared = this.spec()?.tags || [];
    const declaredOrder = new Map(declared.map((t, i) => [t.name, i]));
    return [...seen.values()].sort((a, b) =>
      (declaredOrder.get(a.name) ?? 999) -
      (declaredOrder.get(b.name) ?? 999) ||
      a.name.localeCompare(b.name));
  });

  opsByTag(tag: string): OpenApiOperation[] {
    return this.operations().filter(o => o.tag === tag);
  }

  select(op: OpenApiOperation): void {
    this.active.set(op);
  }

  constructor() {
    this.loadSpec();
  }

  private loadSpec(): void {
    this.http.get<OpenApiSpec>(SPEC_PRIMARY).subscribe({
      next: (s) => {
        this.spec.set(s);
        this.specSource.set('docs build');
      },
      error: () => {
        this.specSource.set('falling back to live /v3/api-docs');
        this.http.get<OpenApiSpec>(SPEC_FALLBACK).subscribe({
          next: (s) => {
            this.spec.set(s);
            this.specSource.set('live /v3/api-docs');
          },
          error: (err) => {
            this.loadError.set(
              `Could not load OpenAPI spec from ${SPEC_PRIMARY} or ` +
              `${SPEC_FALLBACK}: ${err?.message || err}. Run ` +
              `\`make docs-openapi-spec\` to publish it, or bring the ` +
              `BFF up.`);
          },
        });
      },
    });
  }
}
