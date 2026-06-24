/**
 * ApiReferenceComponent — read-only Javadoc-style reference rendered
 * from the merged OpenAPI spec. Shown on the "Reference" tab of
 * DocsShellComponent.
 *
 * Layout: brief tag blurb (from the merged x-snapdragon.blurb), then
 * the operation summary, long description, parameters, request body,
 * responses. No interactive controls.
 *
 * Standalone — consumable directly via federation as './ApiReference'
 * if a host wants ONLY the reference view (e.g. embed in a static
 * documentation site).
 */
import {
  ChangeDetectionStrategy,
  Component,
  Input,
  computed,
  signal,
} from '@angular/core';
import { CommonModule } from '@angular/common';

@Component({
  selector: 'app-api-reference',
  standalone: true,
  imports: [CommonModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [`
    :host { display:block; line-height:1.55; max-width:960px;
            font-family:'Inter','Segoe UI',system-ui,sans-serif; }
    .crumb { color:#6B6B85; font-size:.8rem; margin-bottom:6px; }
    h2 { color:#4A2FB5; font-size:1.3rem; margin:0 0 6px; }
    h3 { color:#4A2FB5; font-size:1rem; margin:18px 0 6px;
         border-bottom:1px solid #E0DCEE; padding-bottom:3px; }
    code, pre, .mono {
      font-family:'JetBrains Mono','Fira Code','Consolas',monospace;
      font-size:.86rem;
    }
    pre {
      background:#F4F2FA; border:1px solid #E0DCEE; border-radius:6px;
      padding:10px 14px; overflow:auto; color:#1F1F2E; white-space:pre;
    }
    .blurb {
      background:#F4F2FA; border-left:3px solid #7C4DFF;
      padding:10px 14px; border-radius:0 4px 4px 0; margin:12px 0;
      font-size:.92rem;
    }
    table { border-collapse:collapse; width:100%; font-size:.88rem; margin:6px 0 14px; }
    th, td { border-bottom:1px solid #E0DCEE; padding:6px 10px; vertical-align:top; }
    th { color:#7C4DFF; text-align:left; font-weight:700; }
    .pill {
      display:inline-block; padding:1px 8px; border-radius:3px;
      font-size:.72rem; font-weight:700;
    }
    .pill-required { background:#FEE2E2; color:#B91C1C; }
    .pill-sse      { background:#DCFCE7; color:#15803D; }
    .pill-binary   { background:#FEF3C7; color:#A16207; }
    .empty { color:#6B6B85; padding:32px 0; text-align:center; }
  `],
  template: `
  @if (active) {
    <div class="crumb">{{ active.tag }} · <code>{{ active.method.toUpperCase() }} {{ active.path }}</code></div>
    <h2>{{ active.op?.summary || (active.method + ' ' + active.path) }}</h2>

    @if (tagBlurb()) {
      <div class="blurb">{{ tagBlurb() }}</div>
    }

    @if (longDesc()) {
      <p>{{ longDesc() }}</p>
    } @else if (active.op?.description) {
      <p>{{ active.op.description }}</p>
    }

    @if (snap().sse) {
      <p><span class="pill pill-sse">SSE</span> Streams via
        <code>text/event-stream</code>. Use the Try-it-out tab to mount
        an in-browser terminal viewer for incoming events.</p>
    }
    @if (snap().binaryUpload) {
      <p><span class="pill pill-binary">Binary upload</span> Multipart
        field <code>{{ snap().binaryUpload.field }}</code>; accepts
        <code>{{ snap().binaryUpload.accept }}</code>.</p>
    }

    @if (parameters().length) {
      <h3>Parameters</h3>
      <table>
        <thead><tr><th>Name</th><th>In</th><th>Type</th><th>Description</th></tr></thead>
        <tbody>
          @for (p of parameters(); track p.name) {
            <tr>
              <td><code>{{ p.name }}</code>
                  @if (p.required) { <span class="pill pill-required">required</span> }</td>
              <td>{{ p.in }}</td>
              <td><code>{{ typeOf(p) }}</code></td>
              <td>{{ p.description || '' }}</td>
            </tr>
          }
        </tbody>
      </table>
    }

    @if (requestBody()) {
      <h3>Request body</h3>
      <table>
        <thead><tr><th>Content type</th><th>Schema</th></tr></thead>
        <tbody>
          @for (entry of requestBodyEntries(); track entry[0]) {
            <tr>
              <td><code>{{ entry[0] }}</code></td>
              <td><code>{{ schemaName($safeNavigationMigration(entry[1]?.schema)) }}</code></td>
            </tr>
          }
        </tbody>
      </table>
      @if (snap().sampleRequest) {
        <h3>Sample request</h3>
        <pre>{{ pretty(snap().sampleRequest) }}</pre>
      }
    }

    <h3>Responses</h3>
    <table>
      <thead><tr><th>Status</th><th>Description</th><th>Content</th></tr></thead>
      <tbody>
        @for (entry of responseEntries(); track entry[0]) {
          <tr>
            <td><code>{{ entry[0] }}</code></td>
            <td>{{ entry[1]?.description || '' }}</td>
            <td>
              @for (c of contentEntries(entry[1]); track c[0]) {
                <div><code>{{ c[0] }}</code></div>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
    @if (snap().sampleResponse) {
      <h3>Sample response</h3>
      <pre>{{ pretty(snap().sampleResponse) }}</pre>
    }
    @if (snap().sampleEvent) {
      <h3>Sample event stream</h3>
      <pre>{{ snap().sampleEvent }}</pre>
    }
  } @else {
    <div class="empty">
      <h2>Pick an operation</h2>
      <p>Choose a path from the navigator on the left to see its
         contract, sample payloads, and try it out.</p>
    </div>
  }
  `,
})
export class ApiReferenceComponent {
  @Input() spec: any = null;
  @Input() active: any = null;

  snap(): any {
    return this.active?.op?.['x-snapdragon'] || {};
  }

  longDesc(): string {
    return this.snap().longDescription || '';
  }

  tagBlurb(): string {
    const tag = this.spec?.tags?.find?.((t: any) => t.name === this.active?.tag);
    return tag?.['x-snapdragon']?.blurb || tag?.description || '';
  }

  parameters(): any[] {
    return this.active?.op?.parameters || [];
  }

  requestBody(): any {
    return this.active?.op?.requestBody;
  }

  requestBodyEntries(): [string, any][] {
    const rb = this.requestBody();
    return rb?.content ? Object.entries(rb.content) : [];
  }

  responseEntries(): [string, any][] {
    return Object.entries(this.active?.op?.responses || {});
  }

  contentEntries(resp: any): [string, any][] {
    return resp?.content ? Object.entries(resp.content) : [];
  }

  typeOf(p: any): string {
    const s = p?.schema;
    if (!s) return '';
    if (s.$ref) return s.$ref.split('/').pop();
    if (s.type) return s.type + (s.format ? `(${s.format})` : '');
    return JSON.stringify(s);
  }

  schemaName(s: any): string {
    if (!s) return '';
    if (s.$ref) return s.$ref.split('/').pop();
    if (s.type === 'object' && s.properties) return 'inline object';
    return s.type || JSON.stringify(s);
  }

  pretty(v: any): string {
    if (typeof v === 'string') return v;
    return JSON.stringify(v, null, 2);
  }
}
