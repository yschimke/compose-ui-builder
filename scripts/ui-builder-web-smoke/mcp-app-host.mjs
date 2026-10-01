// Opens the Wasm editor as an MCP App inside a fake MCP App host, and checks the file round trip.
//
// The host plays ChatGPT/Codex desktop's side of an OpenAI file-extension entrypoint
// (compose-ui-builder#364): `ui/initialize`, `ui/notifications/tool-input` with a `FileInput`,
// `resources/read` / `resources/subscribe` / `openai/resources/write` on an opaque
// `host-resource://` URI with etags, and `ui/update-model-context`. It is the browser half of what
// `McpAppDesignSessionTest` checks against a fake bridge on the JVM.
//
// Two passes (compose-ui-builder#374): the full editor, with the shell's layout placeholder filled
// in as `full` the way compose-preview-server fills it from `uiBuilderMcpAppLayout`, for the file
// round trip; then the focused canvas, with the placeholder left unfilled, for the default layout,
// the switch to the full editor and back, and a comment sent from a node's menu (`ui/message`).
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

const IGNORED_CONSOLE = [
  /Accessing `memory` via `wasmExports` is deprecated/,
];
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
// `?serverLayout=` stands in for the server's `uiBuilderMcpAppLayout` setting: given, it fills in
// the layout placeholder; absent, the placeholder is left as a server that predates it leaves it.
const sandbox = await listen((request, response) => {
  const serverLayout = new URL(request.url, 'http://x').searchParams.get('serverLayout');
  let shell = shellTemplate.replaceAll('__COMPOSE_UI_BUILDER_ASSET_BASE__', assetBase);
  if (serverLayout) shell = shell.replaceAll('__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__', serverLayout);
  response
    .writeHead(200, { 'content-type': TYPES['.html'], 'content-security-policy': csp })
    .end(shell);
});

// The host page: the chat client's side of the bridge, with the file in memory.
const hostPage = `<!doctype html><html><head><meta charset="utf-8"><title>Fake MCP App host</title>
<style>
  body { margin: 0; display: grid; grid-template-columns: var(--aside, 420px) 1fr; height: 100vh;
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
  <h2>Chat (ui/message)</h2><pre id="chat">(no messages)</pre>
  <h2>Model context (ui/update-model-context)</h2><pre id="context">(none)</pre>
  <img id="context-image" alt="" style="max-width: 200px; display: none; margin-top: 4px;
       border: 1px solid #ccc; background: #fff">
  <h2>Saved diff (openai/resources/write)</h2><pre id="diff">(no writes)</pre>
</aside>
<iframe id="app" sandbox="allow-scripts allow-same-origin"></iframe>
<script>
  const uri = 'host-resource://design';
  const fileName = ${JSON.stringify(fileName)};
  const original = ${JSON.stringify(design)};
  const query = new URLSearchParams(location.search);
  const writable = query.get('writable') !== 'false';
  const host = globalThis.fakeHost = {
    text: original, version: 1, calls: [], writes: [], contexts: [], messages: [], errors: [],
    // Every model-context update and message, in the order the app sent them.
    conversation: [],
  };
  const frame = document.getElementById('app');
  const serverLayout = query.get('serverLayout');
  frame.src = 'http://127.0.0.1:${sandbox.port}/' +
    (serverLayout ? '?serverLayout=' + encodeURIComponent(serverLayout) : '');
  if (query.get('aside')) document.body.style.setProperty('--aside', query.get('aside') + 'px');
  const describe = (b) => (b.annotations?.audience ? '[assistant only] ' : '') +
    (b._meta?.['openai/title'] ? '[' + b._meta['openai/title'] + '] ' : '') +
    (b.type === 'image' ? '(image ' + b.mimeType + ', ' + b.data.length + ' base64 chars)' : b.text);
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
        experimental: { 'openai/resource': {}, 'openai/modelContext': {}, 'openai/message': {} },
        updateModelContext: { text: {}, image: {}, structuredContent: {} },
        message: { text: {} },
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
      host.conversation.push('ui/update-model-context');
      const blocks = params.content ?? [];
      document.getElementById('context').textContent = blocks.length === 0 ? '(cleared)' :
        blocks.map(describe).join('\\n\\n');
      const image = blocks.find((b) => b.type === 'image');
      const img = document.getElementById('context-image');
      img.style.display = image ? 'block' : 'none';
      if (image) img.src = 'data:' + image.mimeType + ';base64,' + image.data;
      log('ui/update-model-context (' + blocks.length + ' blocks)');
      return {};
    },
    'ui/message': (params) => {
      host.messages.push(params);
      host.conversation.push('ui/message');
      const options = params._meta?.['openai/message'] ?? {};
      document.getElementById('chat').textContent = host.messages.map((m) =>
        'user -> ' + (m._meta?.['openai/message']?.target ?? 'active') + ':\\n' +
          (m.content ?? []).map(describe).join('\\n')).join('\\n\\n');
      log('ui/message target=' + options.target + ' send=' + options.send);
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
const layoutOf = (frame) =>
  frame.evaluate(() => document.documentElement.getAttribute('data-ui-builder-layout'));
const timeout = Number(process.env.SMOKE_READY_TIMEOUT_MS ?? 180_000);

try {
  // The server's layout setting says `full`: the editor this round trip's coordinates are for.
  await page.goto(`${hostUrl}?serverLayout=full`);
  const frame = await (await page.waitForSelector('#app')).contentFrame();
  await frame.waitForFunction(
    () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
    null,
    { timeout },
  );
  expect(
    (await layoutOf(frame)) === 'full',
    `the server's layout "full" opened ${await layoutOf(frame)}`,
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

// The focused canvas, in a panel the size of a chat host's: the placeholder left unfilled, as a
// server that predates `uiBuilderMcpAppLayout` leaves it. Points are in the frame, for
// `state-actions.uid` framed in a 680 × 900 panel: the centre of the button's label — a short text
// node, whose resize handles, once it is selected, cover its centre — the rows of the menu a
// right-click there opens, and the label's quick editor's Fill for width.
//
// The first right-click selects the label; every one after it lands on the selected label's
// handles. Those used to swallow the secondary press, so no menu opened (compose-ui-builder#378).
const FOCUSED = {
  label: [192, 90],
  quickEdit: [271, 121],
  comment: [271, 169],
  fillWidth: [345, 163],
  fullEditor: [533, 28],
  focusedCanvas: [513, 28],
};
const panel = await browser.newPage({ viewport: { width: 1100, height: 900 }, locale: 'en-US' });
panel.on('pageerror', (error) => pageErrors.push(String(error)));
panel.on('console', (message) => {
  if (message.type() === 'error' && !IGNORED_CONSOLE.some((it) => it.test(message.text()))) {
    pageErrors.push(message.text());
  }
});
const panelHost = () => panel.evaluate(() => JSON.parse(JSON.stringify(globalThis.fakeHost)));
try {
  await panel.goto(hostUrl);
  const frame = await (await panel.waitForSelector('#app')).contentFrame();
  await frame.waitForFunction(
    () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
    null,
    { timeout },
  );
  expect((await layoutOf(frame)) === 'focused', `the default layout is ${await layoutOf(frame)}`);
  const box = await (await panel.$('#app')).boundingBox();
  const at = ([x, y]) => [box.x + x, box.y + y];
  const click = (point, options) => panel.mouse.click(...at(point), options);
  const settle = () => panel.waitForTimeout(800);
  await panel.waitForTimeout(3_000);
  let state = await panelHost();
  expect(state.writes.length === 0, `the focused canvas wrote on open (${state.writes.length})`);
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused.png') });

  // An empty comment sends nothing: Enter in the empty field, then Escape.
  await click(FOCUSED.label, { button: 'right' });
  await settle();
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused-menu.png') });
  await click(FOCUSED.comment);
  await settle();
  state = await panelHost();
  const quiet = state.conversation.length;
  await panel.keyboard.press('Enter');
  await panel.waitForTimeout(1_500);
  state = await panelHost();
  expect(state.messages.length === 0, `an empty comment sent ${state.messages.length} messages`);
  expect(
    state.conversation.length === quiet,
    `an empty comment sent ${state.conversation.slice(quiet).join(', ')}`,
  );
  await panel.keyboard.press('Escape');
  await settle();

  // A comment: typed into the field beside the node, sent with Enter. Exactly one model-context
  // update — the node's picture and the assistant-only detail — and then exactly one message.
  const comment = 'Make this label say Start';
  await click(FOCUSED.label, { button: 'right' });
  await settle();
  // The label is selected now, so this right-click landed on its resize handles.
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused-node-menu.png') });
  await click(FOCUSED.comment);
  await settle();
  await panel.keyboard.type(comment);
  await settle();
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused-comment.png') });
  state = await panelHost();
  const before = state.conversation.length;
  await panel.keyboard.press('Enter');
  await panel
    .waitForFunction(() => globalThis.fakeHost.messages.length > 0, null, { timeout: 20_000 })
    .catch(() => {});
  await panel.waitForTimeout(1_500);
  state = await panelHost();
  expect(
    JSON.stringify(state.conversation.slice(before)) ===
      JSON.stringify(['ui/update-model-context', 'ui/message']),
    `a comment sent ${state.conversation.slice(before).join(', ') || 'nothing'}`,
  );
  const message = state.messages[0];
  expect(message?.role === 'user', `the comment's role was ${message?.role}`);
  expect(
    message?._meta?.['openai/message']?.target === 'active' &&
      message?._meta?.['openai/message']?.send === true,
    `the comment's openai/message was ${JSON.stringify(message?._meta)}`,
  );
  expect(message?.content?.[0]?.text === comment, 'the message did not lead with the comment');
  expect(
    message?.content?.[1]?._meta?.['openai/title'] === 'Text · label',
    `the node block was titled ${message?.content?.[1]?._meta?.['openai/title']}`,
  );
  const commentContext = state.contexts.at(-1)?.content ?? [];
  expect(
    commentContext[0]?.type === 'image' && commentContext[0]?.mimeType === 'image/png',
    'the comment context had no PNG of the node',
  );
  expect(
    commentContext.at(-1)?.annotations?.audience?.[0] === 'assistant',
    'the comment context had no assistant-only detail',
  );
  expect(state.writes.length === 0, 'a comment wrote the file');
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused-comment-sent.png') });

  // Quick edit still edits in the focused canvas, and autosave writes it through the host.
  await click(FOCUSED.label, { button: 'right' });
  await settle();
  await click(FOCUSED.quickEdit);
  await settle();
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused-quick-edit.png') });
  await click(FOCUSED.fillWidth);
  await panel
    .waitForFunction(() => globalThis.fakeHost.writes.length > 0, null, { timeout: 20_000 })
    .catch(() => {});
  state = await panelHost();
  expect(
    state.writes.length === 1 && state.writes[0].ifMatch === 'v1',
    `quick edit in the focused canvas saved ${state.writes.length} times`,
  );

  // Full editor, and back: a live switch that writes nothing.
  await click(FOCUSED.fullEditor);
  await settle();
  expect((await layoutOf(frame)) === 'full', `Full editor switched to ${await layoutOf(frame)}`);
  if (shots) await panel.screenshot({ path: join(shots, 'mcp-app-focused-to-full.png') });
  await click(FOCUSED.focusedCanvas);
  await settle();
  expect(
    (await layoutOf(frame)) === 'focused',
    `Focused canvas switched to ${await layoutOf(frame)}`,
  );
  await panel.waitForTimeout(2_000);
  state = await panelHost();
  expect(state.writes.length === 1, `switching layouts wrote the file (${state.writes.length})`);
  expect(state.errors.length === 0, `host errors: ${state.errors.join('; ')}`);
} catch (error) {
  failures.push(`focused canvas: ${error.message.split('\n')[0]}`);
}
failures.push(...pageErrors.map((it) => `page error: ${it}`));
console.log(failures.length === 0 ? 'ok   mcp-app host round trip, focused canvas and comment' : `FAIL mcp-app host\n  ${failures.join('\n  ')}`);
await browser.close();
for (const it of [assets, sandbox, hostServer]) it.server.close();
process.exit(failures.length === 0 ? 0 : 1);
