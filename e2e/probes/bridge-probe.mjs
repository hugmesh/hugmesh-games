// Bridge-load probe: serve the deduped bundle with cross-origin-isolation
// headers and boot one area in headless Chromium. PASS = the GDExtension
// actually loads (no "Cannot get class 'HostBridge'" / placeholder warnings).
// Run inside the e2e image: node results/bridge-probe.mjs
import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname } from 'node:path';
import { chromium } from '@playwright/test';

const ROOT = new URL('../results/bridge-probe-bundle', import.meta.url).pathname;
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.wasm': 'application/wasm', '.pck': 'application/octet-stream', '.png': 'image/png' };

const server = http.createServer(async (req, res) => {
  try {
    const path = join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
    const body = await readFile(path);
    res.writeHead(200, {
      'Content-Type': MIME[extname(path)] ?? 'application/octet-stream',
      'Cross-Origin-Opener-Policy': 'same-origin',
      'Cross-Origin-Embedder-Policy': 'require-corp',
      'Cross-Origin-Resource-Policy': 'cross-origin',
    });
    res.end(body);
  } catch {
    res.writeHead(404).end('nope');
  }
});
await new Promise(r => server.listen(0, '127.0.0.1', r));
const port = server.address().port;

const browser = await chromium.launch();
const page = await browser.newPage();
const lines = [];
page.on('console', m => lines.push(m.text()));
page.on('pageerror', e => lines.push('PAGEERROR: ' + e.message));
await page.goto(`http://127.0.0.1:${port}/bramblewick/index.html`, { waitUntil: 'load' });
await page.waitForTimeout(20_000);
await browser.close();
server.close();

const bad = lines.filter(l =>
  l.includes("Cannot get class 'HostBridge'") ||
  l.includes("Cannot get class 'BridgeGlue'") ||
  l.includes('placeholder will be created') ||
  l.includes("Can't open dynamic library"));
const interesting = lines.filter(l => /error|warn|bridge|world|extension/i.test(l)).slice(0, 30);
console.log('--- interesting console lines ---');
for (const l of interesting) console.log(' ', l.slice(0, 160));
if (bad.length) {
  console.log(`\nBRIDGE-PROBE: FAIL (${bad.length} bridge-load errors)`);
  process.exit(1);
}
console.log('\nBRIDGE-PROBE: PASS — GDExtension loaded, no placeholder classes');
