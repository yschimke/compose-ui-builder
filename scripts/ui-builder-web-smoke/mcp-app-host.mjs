// Opens the Wasm editor as an MCP App inside a fake MCP App host, and checks the file round trip.
//
// The host plays ChatGPT/Codex desktop's side of an OpenAI file-extension entrypoint
// (compose-ui-builder#364): `ui/initialize`, `ui/notifications/tool-input` with a `FileInput`,
// `resources/read` / `resources/subscribe` / `openai/resources/write` on an opaque
// `host-resource://` URI with etags, and `ui/update-model-context`. It is the browser half of what
// `McpAppDesignSessionTest` checks against a fake bridge on the JVM.
//
// Three origins, as a real host has them: the host page, the sandbox frame the MCP App shell runs
// in, and the server origin serving the editor archive (named in the shell's `<base href>`, and
// the resource's CSP `resourceDomains` / `connectDomains`). The archive origin answers with CORS
// headers, because module scripts and the Wasm fetch are cross-origin from the frame.
//
// Usage: node mcp-app-host.mjs <wasmDist directory> <design .uid> [screenshot directory]

import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(process.argv[2] ?? join(here, '../../ui-builder/build/wasmDist'));
const designPath = resolve(
  process.argv[3] ?? join(here, '../../docs/design/fixtures/ui-builder/state-actions.uid'),
);
const shots = process.argv[4] ? resolve(process.argv[4]) : null;
const shellTemplate = await readFile(
  join(here, '../../ui-builder-web/src/mcp-app/ui-builder-mcp-app.html'),
  'utf8',
);
const design = await readFile(designPath, 'utf8');
const fileName = designPath.split('/').pop();

const IGNORED_CONSOLE = [/Accessing `memory` via `wasmExports` is deprecated/];
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.wasm': 'application/wasm',
  '.json': 'application/json',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

function listen(handler) {
  const server = createServer(handler);
  return new Promise((ready) =>
    server.listen(0, '127.0.0.1', () => ready({ server, port: server.address().port })),
  );
}

// The editor archive, as the MCP server would serve it: any origin may load it.
const assets = await listen(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname));
  const file = join(root, path);
  if (!file.startsWith(root)) return response.writeHead(403).end();
  try {
    const body = await readFile(file);
    response
      .writeHead(200, {
        'content-type': TYPES[extname(file)] ?? 'application/octet-stream',
        'access-control-allow-origin': '*',
      })
      .end(body);
  } catch {
    response.writeHead(404, { 'access-control-allow-origin': '*' }).end();
  }
});
const assetBase = `http://127.0.0.1:${assets.port}/`;

// The sandbox origin the host renders the MCP App resource in: the shell, with the server's one
// substitution made, and a CSP of the shape MCP Apps hosts derive from `_meta.ui.csp`.
const csp = [
  "default-src 'none'",
  `script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval' ${assetBase}`,
  `style-src 'self' 'unsafe-inline' ${assetBase}`,
  `img-src 'self' data: blob: ${assetBase}`,
  `font-src 'self' data: ${assetBase}`,
  `connect-src 'self' ${assetBase}`,
].join('; ');
const sandbox = await listen((request, response) => {
  response
    .writeHead(200, { 'content-type': TYPES['.html'], 'content-security-policy': csp })
    .end(shellTemplate.replaceAll('__COMPOSE_UI_BUILDER_ASSET_BASE__', assetBase));
});

// The host page: the chat client's side of the bridge, with the file in memory.
const hostPage = `<!doctype html><html><head><meta charset="utf-8"><title>Fake MCP App host</title>
<style>
  body { margin: 0; display: grid; grid-template-columns: 420px 1fr; height: 100vh;
         font: 12px/1.4 system-ui, sans-serif; background: #f3f0f7; }
  aside { overflow: auto; padding: 10px; border-right: 1px solid #ccc; }
  h2 { font-size: 13px; margin: 10px 0 4px; }
  pre { white-space: pre-wrap; background: #fff; padding: 6px; border-radius: 4px; margin: 0;
        font: 11px/1.35 ui-monospace, monospace; max-height: 30vh; overflow: auto; }
  .del { background: #ffd7d5; } .add { background: #d2f5d6; }
  iframe { border: 0; width: 100%; height: 100%; background: #fff; }
</style></head><body>
<aside>
  <div><b>Fake MCP App host</b> &middot; file entrypoint <code>.uid</code> &middot;
    <code>host-resource://design</code></div>
  <h2>Host log</h2><pre id="log"></pre>
  <h2>Model context (ui/update-model-context)</h2><pre id="context">(none)</pre>
  <h2>Saved diff (openai/resources/write)</h2><pre id="diff">(no writes)</pre>
</aside>
<iframe id="app" sandbox="allow-scripts allow-same-origin" src="http://127.0.0.1:${sandbox.port}/"></iframe>
<script>
  const uri = 'host-resource://design';
  const fileName = ${JSON.stringify(fileName)};
  const original = ${JSON.stringify(design)};
  const writable = new URLSearchParams(location.search).get('writable') !== 'false';
  const host = globalThis.fakeHost = {
    text: original, version: 1, calls: [], writes: [], contexts: [], errors: [],
  };
  const frame = document.getElementById('app');
  const log = (line) => {
    host.calls.push(line);
    document.getElementById('log').textContent = host.calls.slice(-14).join('\\n');
  };
  const send = (message) => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...message }, '*');
  const etag = () => 'v' + host.version;
  // A line diff (LCS) of what was written, changed lines only, for a person to read.
  function renderDiff(before, after) {
    const a = before.split('\\n'), b = after.split('\\n');
    const n = a.length, m = b.length;
    const lcs = Array.from({ length: n + 1 }, () => new Int32Array(m + 1));
    for (let i = n - 1; i >= 0; i--)
      for (let j = m - 1; j >= 0; j--)
        lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    const out = [];
    let i = 0, j = 0;
    while (i < n || j < m) {
      if (i < n && j < m && a[i] === b[j]) { i++; j++; }
      else if (j < m && (i === n || lcs[i][j + 1] >= lcs[i + 1][j])) out.push(['add', '+ ' + b[j++]]);
      else out.push(['del', '- ' + a[i++]]);
    }
    const pre = document.getElementById('diff');
    pre.textContent = '';
    for (const [kind, text] of out.slice(0, 60)) {
      const line = document.createElement('div');
      line.className = kind; line.textContent = text; pre.appendChild(line);
    }
  }
  host.externalWrite = (text) => {
    host.text = text; host.version++;
    log('external edit -> ' + etag());
    send({ method: 'notifications/resources/updated', params: { uri } });
  };
  const handlers = {
    'ui/initialize': () => ({
      protocolVersion: '2026-01-26',
      hostInfo: { name: 'fake-mcp-app-host', version: '1' },
      hostCapabilities: {
        experimental: { 'openai/resource': {}, 'openai/modelContext': {} },
        updateModelContext: { text: {}, structuredContent: {} },
        openLinks: {},
      },
      hostContext: { theme: 'light', displayMode: 'fullscreen' },
    }),
    'resources/read': (params) => {
      if (params.uri !== uri) throw new Error('unknown resource ' + params.uri);
      const representation = params._meta?.['openai/resource']?.representation;
      log('resources/read representation=' + representation + ' -> ' + etag());
      return { contents: [{ uri, mimeType: 'application/json', text: host.text,
        _meta: { 'openai/resource': { etag: etag(), writable } } }] };
    },
    'resources/subscribe': (params) => { log('resources/subscribe'); return {}; },
    'resources/unsubscribe': () => ({}),
    'openai/resources/write': (params) => {
      if (params.uri !== uri || !writable) throw new Error('not writable');
      host.writes.push(params);
      if (params.ifMatch && params.ifMatch !== etag()) {
        log('write ifMatch=' + params.ifMatch + ' -> conflict ' + etag());
        return { outcome: 'conflict', etag: etag() };
      }
      const before = host.text;
      host.text = params.text; host.version++;
      log('write ifMatch=' + params.ifMatch + ' -> saved ' + etag());
      renderDiff(before, host.text);
      return { outcome: 'saved', etag: etag() };
    },
    'ui/update-model-context': (params) => {
      host.contexts.push(params);
      const blocks = params.content ?? [];
      document.getElementById('context').textContent = blocks.length === 0 ? '(cleared)' :
        blocks.map((b) => (b.annotations?.audience ? '[assistant only] ' : '') +
          (b._meta?.['openai/title'] ? '[' + b._meta['openai/title'] + '] ' : '') + b.text)
          .join('\\n\\n');
      log('ui/update-model-context (' + blocks.length + ' blocks)');
      return {};
    },
    'ui/open-link': () => ({}),
  };
  addEventListener('message', async (event) => {
    if (event.source !== frame.contentWindow) return;
    const message = event.data;
    if (!message || message.jsonrpc !== '2.0') return;
    if (message.method === 'ui/notifications/initialized') {
      log('initialized; sending tool-input FileInput');
      send({ method: 'ui/notifications/tool-input',
        params: { arguments: { file: { name: fileName, resourceUri: uri } } } });
      return;
    }
    if (!message.method || message.id == null) return;
    const handler = handlers[message.method];
    try {
      if (!handler) throw new Error('unsupported ' + message.method);
      send({ id: message.id, result: await handler(message.params ?? {}) });
    } catch (error) {
      host.errors.push(String(error));
      send({ id: message.id, error: { code: -32603, message: String(error.message ?? error) } });
    }
  });
</script></body></html>`;
const hostServer = await listen((request, response) => {
  response.writeHead(200, { 'content-type': TYPES['.html'] }).end(hostPage);
});
const hostUrl = `http://localhost:${hostServer.port}/`;

const browser = await chromium.launch({
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
if (shots) await mkdir(shots, { recursive: true });

const failures = [];
const expect = (condition, message) => {
  if (!condition) failures.push(message);
};
const page = await browser.newPage({ viewport: { width: 1600, height: 960 }, locale: 'en-US' });
const pageErrors = [];
page.on('pageerror', (error) => pageErrors.push(String(error)));
page.on('console', (message) => {
  if (message.type() === 'error' && !IGNORED_CONSOLE.some((it) => it.test(message.text()))) {
    pageErrors.push(message.text());
  }
});
const host = () => page.evaluate(() => JSON.parse(JSON.stringify(globalThis.fakeHost)));
const timeout = Number(process.env.SMOKE_READY_TIMEOUT_MS ?? 180_000);

try {
  await page.goto(hostUrl);
  const frame = await (await page.waitForSelector('#app')).contentFrame();
  await frame.waitForFunction(
    () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
    null,
    { timeout },
  );
  let state = await host();
  expect(
    state.calls.some((it) => it === 'resources/read representation=text -> v1'),
    'the editor did not read the file as text',
  );
  expect(state.calls.includes('resources/subscribe'), 'the editor did not subscribe to the file');
  // Let an autosave that should not happen have its chance.
  await page.waitForTimeout(3_000);
  state = await host();
  expect(state.writes.length === 0, `opening the design wrote it (${state.writes.length} writes)`);
  if (shots) await page.screenshot({ path: join(shots, 'mcp-app-opened.png') });

  // Select a layer on the canvas: it becomes model context. The point is the fixture's button at
  // this viewport, as the editor frames `state-actions.uid` (the root column cannot be deleted,
  // which is why the step below needs a child). A different design or viewport needs another one.
  // Waits for a context sent after the click, not for any context: opening the design can
  // already have sent one for the root, and a wait that returned on that raced the click.
  const box = await (await page.$('#app')).boundingBox();
  const openedContexts = state.contexts.length;
  await page.mouse.click(box.x + 180, box.y + 209);
  await page.waitForFunction(
    (before) => globalThis.fakeHost.contexts.length > before,
    openedContexts,
    { timeout: 20_000 },
  ).catch(() => {});
  state = await host();
  const context = state.contexts.at(-1);
  expect(
    context?.content?.[0]?._meta?.['openai/title'],
    'selecting a layer sent no titled model context',
  );
  expect(
    context?.content?.[1]?.annotations?.audience?.[0] === 'assistant',
    'the model context had no assistant-only detail block',
  );

  // Select its parent, the button, through the breadcrumb (the inspector has opened beside the
  // canvas by now), and delete it: an edit, which autosaves with the etag it read. The label
  // itself cannot go: a button's content slot must have one child.
  // Waits for the selection to move before deleting, rather than for a fixed half second: on a
  // slow runner the Delete landed while the label was still selected, and a button's only label
  // cannot be deleted, so nothing was saved.
  const contextsBefore = state.contexts.length;
  await page.mouse.click(box.x + 160, box.y + 140);
  await page.waitForFunction(
    (before) => globalThis.fakeHost.contexts.length > before,
    contextsBefore,
    { timeout: 20_000 },
  ).catch(() => {});
  await page.keyboard.press('Delete');
  await page.waitForFunction(() => globalThis.fakeHost.writes.length > 0, null, { timeout: 20_000 })
    .catch(() => {});
  state = await host();
  expect(state.writes[0]?.ifMatch === 'v1', `the first save named ${state.writes[0]?.ifMatch}`);
  expect(state.version === 2, `the save did not land (host at v${state.version})`);
  await page.waitForTimeout(1_000);
  if (shots) await page.screenshot({ path: join(shots, 'mcp-app-saved.png') });

  // An agent edits the file while the editor is clean: the editor reloads it.
  const reads = state.calls.filter((it) => it.startsWith('resources/read')).length;
  await page.evaluate((text) => globalThis.fakeHost.externalWrite(text), design);
  await page.waitForFunction(
    (before) =>
      globalThis.fakeHost.calls.filter((it) => it.startsWith('resources/read')).length > before,
    reads,
    { timeout: 20_000 },
  ).catch(() => {});
  await page.waitForTimeout(2_000);
  state = await host();
  expect(
    state.calls.some((it) => it === 'resources/read representation=text -> v3'),
    'the editor did not read the external edit',
  );
  expect(state.writes.length === 1, `the external edit was written back (${state.writes.length})`);
  expect(state.errors.length === 0, `host errors: ${state.errors.join('; ')}`);
} catch (error) {
  failures.push(`not ready: ${error.message.split('\n')[0]}`);
}
failures.push(...pageErrors.map((it) => `page error: ${it}`));
console.log(failures.length === 0 ? 'ok   mcp-app host round trip' : `FAIL mcp-app host\n  ${failures.join('\n  ')}`);
await browser.close();
for (const it of [assets, sandbox, hostServer]) it.server.close();
process.exit(failures.length === 0 ? 0 : 1);
