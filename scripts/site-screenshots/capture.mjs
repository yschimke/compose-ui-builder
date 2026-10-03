// Captures the landing page's screenshots (site/img) from a built or released Wasm editor.
//
// Every picture is the real editor: the archive's MCP App shell (ui-builder-web/src/mcp-app/),
// opened in a small host page the way an agent's chat client opens it, on designs committed under
// docs/design/fixtures/ui-builder/designs/. The chat column beside it is this script's own
// scenery: it stages the prompt and the agent's reply, while the comment that appears there in the
// last step is the real `ui/message` the editor sends. Nothing needs a server, so the same script
// runs in the Pages workflow against the latest release and locally against a fresh build.
//
// Usage: node capture.mjs <wasmDist or unpacked web zip> <out dir>
//
// The host plumbing is the same protocol `scripts/ui-builder-web-smoke/mcp-app-host.mjs` checks;
// see docs/design/UI_BUILDER_MCP_APP_HOST.md. When the editor's chrome moves, the click points in
// SCENES may need adjusting: run this, look at the PNGs, and fix the numbers.

import { createServer } from 'node:http';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, extname, join, normalize, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';
import { replayCandidateOperations } from '../ui-builder/replay-candidate.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../..');
const root = resolve(process.argv[2] ?? join(repo, 'ui-builder/build/wasmDist'));
const out = resolve(process.argv[3] ?? join(repo, 'site/img'));
const only = process.env.SHOTS?.split(',');
await mkdir(out, { recursive: true });

const shellTemplate = await readFile(
  join(root, 'mcp-app/ui-builder-mcp-app.html'),
  'utf8',
).catch(() => readFile(join(repo, 'ui-builder-web/src/mcp-app/ui-builder-mcp-app.html'), 'utf8'));

/** A committed operations fixture, replayed into the `.uid` document an MCP App opens. */
async function design(name, title) {
  const operations = JSON.parse(
    await readFile(join(repo, 'docs/design/fixtures/ui-builder/designs', `${name}.json`), 'utf8'),
  );
  const { document } = replayCandidateOperations(operations);
  if (title) document.title = title;
  return JSON.stringify(document, null, 2);
}

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
  '.css': 'text/css',
  '.uid': 'application/json',
};

function listen(handler) {
  const server = createServer(handler);
  return new Promise((ready) =>
    server.listen(0, '127.0.0.1', () => ready({ server, port: server.address().port })),
  );
}

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

const sandbox = await listen((request, response) => {
  const layout = new URL(request.url, 'http://x').searchParams.get('layout') ?? 'focused';
  const shell = shellTemplate
    .replaceAll('__COMPOSE_UI_BUILDER_ASSET_BASE__', assetBase)
    .replaceAll('__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__', layout);
  response.writeHead(200, { 'content-type': TYPES['.html'] }).end(shell);
});

// The host: an agent chat on the left, the MCP App on the right. `chat` is the staged transcript,
// `{ role: 'user' | 'agent', text }`; messages the editor sends are appended to it as they arrive.
function hostPage({ fileName, text, layout, chat }) {
  return `<!doctype html><html><head><meta charset="utf-8"><title>Agent chat</title>
<style>
  :root { --bg: #f6f4f9; --panel: #ffffff; --fg: #1d1b20; --muted: #625b71; --line: #e4e0ea;
          --user: #e9ddff; --accent: #6750a4; }
  * { box-sizing: border-box; }
  body { margin: 0; height: 100vh; display: grid; grid-template-columns: ${chat ? '400px 1fr' : '1fr'};
         font: 14px/1.45 Roboto, 'Segoe UI', system-ui, sans-serif; color: var(--fg); background: var(--bg); }
  aside { display: ${chat ? 'flex' : 'none'}; flex-direction: column; border-right: 1px solid var(--line); background: var(--panel); min-height: 0; }
  header { padding: 14px 18px; border-bottom: 1px solid var(--line); font-weight: 600; display: flex; gap: 10px; align-items: center; }
  header .dot { width: 26px; height: 26px; border-radius: 8px; background: linear-gradient(135deg, #6750a4, #00a6a6); }
  header small { display: block; font-weight: 400; color: var(--muted); font-size: 12px; }
  #thread { flex: 1; overflow: auto; padding: 16px 18px; display: flex; flex-direction: column; gap: 12px; }
  .msg { max-width: 92%; padding: 10px 13px; border-radius: 16px; white-space: pre-wrap; }
  .user { align-self: flex-end; background: var(--user); border-bottom-right-radius: 4px; }
  .agent { align-self: flex-start; background: var(--bg); border-bottom-left-radius: 4px; }
  .agent code, .user code { font: 12px ui-monospace, monospace; background: rgba(0,0,0,.06); padding: 1px 4px; border-radius: 4px; }
  .chip { display: flex; width: fit-content; gap: 6px; align-items: center; margin-top: 6px; font-size: 12px; color: var(--muted);
          background: #fff; border: 1px solid var(--line); border-radius: 999px; padding: 2px 10px; }
  .chip img { height: 18px; border-radius: 3px; }
  .tool { align-self: flex-start; font-size: 12px; color: var(--muted); border: 1px dashed var(--line); border-radius: 10px; padding: 6px 10px; }
  footer { padding: 12px 14px; border-top: 1px solid var(--line); }
  .composer { border: 1px solid var(--line); border-radius: 14px; padding: 10px 12px; color: var(--muted); background: var(--bg); }
  main { min-width: 0; padding: ${chat ? '14px' : '0'}; }
  iframe { border: 0; width: 100%; height: 100%; background: #fff; border-radius: ${chat ? '14px' : '0'};
           box-shadow: ${chat ? '0 1px 3px rgba(0,0,0,.08), 0 8px 24px rgba(0,0,0,.06)' : 'none'}; }
</style></head><body>
<aside>
  <header><span class="dot"></span><div>Your coding agent<small>${fileName} · Compose UI Builder app</small></div></header>
  <div id="thread"></div>
  <footer><div class="composer">Reply…</div></footer>
</aside>
<main><iframe id="app" sandbox="allow-scripts allow-same-origin" src="http://127.0.0.1:${sandbox.port}/?layout=${layout}"></iframe></main>
<script>
  const uri = 'host-resource://design';
  const fileName = ${JSON.stringify(fileName)};
  const host = globalThis.fakeHost = { text: ${JSON.stringify(text)}, version: 1, messages: [], contexts: [], writes: [] };
  const frame = document.getElementById('app');
  const thread = document.getElementById('thread');
  const escape = (s) => s.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
  globalThis.say = (role, text, extra = '') => {
    const div = document.createElement('div');
    div.className = 'msg ' + role;
    div.innerHTML = escape(text).replace(/\`([^\`]+)\`/g, '<code>$1</code>') + extra;
    thread.appendChild(div);
    thread.scrollTop = thread.scrollHeight;
  };
  globalThis.tool = (text) => {
    const div = document.createElement('div');
    div.className = 'tool';
    div.textContent = text;
    thread.appendChild(div);
  };
  for (const m of ${JSON.stringify(chat ?? [])}) m.role === 'tool' ? tool(m.text) : say(m.role, m.text);
  const send = (message) => frame.contentWindow.postMessage({ jsonrpc: '2.0', ...message }, '*');
  const etag = () => 'v' + host.version;
  host.externalWrite = (text) => {
    host.text = text; host.version++;
    send({ method: 'notifications/resources/updated', params: { uri } });
  };
  const handlers = {
    'ui/initialize': () => ({
      protocolVersion: '2026-01-26',
      hostInfo: { name: 'landing-page-host', version: '1' },
      hostCapabilities: {
        experimental: { 'openai/resource': {}, 'openai/modelContext': {}, 'openai/message': {} },
        updateModelContext: { text: {}, image: {}, structuredContent: {} },
        message: { text: {} },
        openLinks: {},
      },
      hostContext: { theme: 'light', displayMode: 'fullscreen' },
    }),
    'resources/read': () => ({ contents: [{ uri, mimeType: 'application/json', text: host.text,
      _meta: { 'openai/resource': { etag: etag(), writable: true } } }] }),
    'resources/subscribe': () => ({}),
    'resources/unsubscribe': () => ({}),
    'openai/resources/write': (params) => {
      host.writes.push(params);
      host.text = params.text; host.version++;
      return { outcome: 'saved', etag: etag() };
    },
    'ui/update-model-context': (params) => { host.contexts.push(params); return {}; },
    'ui/message': (params) => {
      host.messages.push(params);
      const [first, ...rest] = params.content ?? [];
      const node = rest.find((b) => b._meta?.['openai/title'])?._meta['openai/title'];
      const image = host.contexts.at(-1)?.content?.find((b) => b.type === 'image');
      const chip = node ? '<div class="chip">' +
        (image ? '<img src="data:' + image.mimeType + ';base64,' + image.data + '">' : '') +
        escape(node) + '</div>' : '';
      say('user', first?.text ?? '', chip);
      return {};
    },
    'ping': () => ({}),
    'ui/open-link': () => ({}),
  };
  window.addEventListener('message', (event) => {
    const message = event.data;
    if (!message || message.jsonrpc !== '2.0') return;
    if (message.method === 'ui/notifications/initialized') {
      send({ method: 'ui/notifications/tool-input', params: { arguments: { file: { name: fileName, resourceUri: uri } } } });
      return;
    }
    if (message.id === undefined || !message.method) return;
    const handler = handlers[message.method];
    try {
      if (!handler) throw new Error('unsupported ' + message.method);
      send({ id: message.id, result: handler(message.params ?? {}) });
    } catch (error) {
      send({ id: message.id, error: { code: -32601, message: String(error.message ?? error) } });
    }
  });
</script></body></html>`;
}

let currentPage = '';
const hostServer = await listen((request, response) => {
  response.writeHead(200, { 'content-type': TYPES['.html'] }).end(currentPage);
});

const browser = await chromium.launch({
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});

/** Opens one host page and waits for the editor inside it to be ready. */
async function open(options, viewport) {
  currentPage = hostPage(options);
  const page = await browser.newPage({ viewport, deviceScaleFactor: 2, locale: 'en-US' });
  page.on('pageerror', (error) => console.warn('page error:', String(error)));
  await page.goto(`http://127.0.0.1:${hostServer.port}/`);
  const frame = await (await page.waitForSelector('#app')).contentFrame();
  await frame.waitForFunction(
    () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
    null,
    { timeout: 240_000 },
  );
  await page.waitForTimeout(3_000);
  const box = await (await page.$('#app')).boundingBox();
  const click = ([x, y], options) => page.mouse.click(box.x + x, box.y + y, options);
  return { page, click, settle: (ms = 900) => page.waitForTimeout(ms) };
}

// The Wear list template exactly as New from template seeds it (SiteTemplateDesignsTest keeps it
// current), and the agent's result, replayed from a committed design.
const WEAR_LIST = await readFile(join(repo, 'site/designs/wear-list.uid'), 'utf8');
const WEAR_LIBRARY = await design('jetcaster-wear-library', 'Wear list screen');

const PROMPT =
  'Turn this Wear list into the Jetcaster library: a "Your library" title, buttons for ' +
  'Latest episodes and Podcasts, then a Queue section with Up Next.';

const SCENES = {
  // Step 1: a template in the full editor, the canvas unrolled on the left and two display
  // variants previewed on the right, switched on from the Screen dock's "Also compare" chips.
  async 'step-1-template'() {
    const { page, click, settle } = await open(
      { fileName: 'wear-list.uid', text: WEAR_LIST, layout: 'full' },
      { width: 1280, height: 800 },
    );
    const at = (name, fallback) => JSON.parse(process.env[name] ?? fallback);
    await click(at('SCREEN_DOCK', '[1254, 232]'));
    await settle();
    await click(at('COMPARE_DARK', '[954, 290]'));
    await settle();
    await click(at('COMPARE_FONT', '[1099, 290]'));
    await settle();
    await click(at('SCREEN_DOCK_CLOSE', '[1197, 138]'));
    await page.mouse.move(5, 795);
    await settle(2_500);
    return page;
  },
  // Step 2: the prompt, the agent's edit, and a comment on a node, which the editor sends into
  // the chat with the node attached.
  async 'step-2-iterate'() {
    const { page, click, settle } = await open(
      {
        fileName: 'wear-list.uid',
        text: WEAR_LIBRARY,
        layout: 'focused',
        chat: [
          { role: 'user', text: PROMPT },
          { role: 'tool', text: 'Edited wear-list.uid · 13 nodes' },
          { role: 'agent', text: 'Done. The editor beside us has reloaded it, so point at anything you want changed.' },
        ],
      },
      { width: 1200, height: 760 },
    );
    // Points in the editor frame: the Podcasts chip, and the Comment row of the menu that opens
    // beside it. Override with STEP3_TARGET / STEP3_COMMENT while re-tuning (DEBUG_SHOTS=1 saves
    // the open menu).
    const target = JSON.parse(process.env.STEP3_TARGET ?? '[400, 330]');
    const comment = JSON.parse(process.env.STEP3_COMMENT ?? '[479, 129]');
    await click(target, { button: 'right' });
    await settle();
    if (process.env.DEBUG_SHOTS) await page.screenshot({ path: join(out, 'debug-step-3-menu.png') });
    await click(comment);
    await settle();
    await page.keyboard.type('Make this one stand out: use the primary colour');
    await settle();
    await page.keyboard.press('Enter');
    await page
      .waitForFunction(() => globalThis.fakeHost.messages.length > 0, null, { timeout: 20_000 })
      .catch(() => console.warn('step-3: the comment did not reach the chat; check STEP3_TARGET'));
    await settle(1_500);
    return page;
  },
  // The hero: the full editor, with the chat beside it.
  async hero() {
    const { page, click, settle } = await open(
      {
        fileName: 'wear-list.uid',
        text: WEAR_LIBRARY,
        layout: 'full',
        chat: [
          { role: 'user', text: PROMPT },
          { role: 'tool', text: 'Edited wear-list.uid · 13 nodes' },
          { role: 'agent', text: 'Done: two ListHeaders and three buttons, all real Wear Material 3 components. Want the Compose code next?' },
        ],
      },
      { width: 1440, height: 860 },
    );
    // The toolbar's code toggle, in the editor frame: the design and its Compose source together.
    await click(JSON.parse(process.env.HERO_CODE_TOGGLE ?? '[753, 85]'));
    await settle(2_500);
    return page;
  },
  // Step 3: the generated Compose source beside the canvas.
  async 'step-3-export'() {
    const { page, click, settle } = await open(
      { fileName: 'wear-list.uid', text: WEAR_LIBRARY, layout: 'full' },
      { width: 1280, height: 800 },
    );
    // The toolbar's code toggle, in the editor frame.
    await click(JSON.parse(process.env.CODE_TOGGLE ?? '[1022, 86]'));
    await settle(2_500);
    return page;
  },
  // Features: a Material 3 tablet design.
  async 'feature-material'() {
    const { page } = await open(
      { fileName: 'gmail.uid', text: await design('google-gmail-tablet'), layout: 'full' },
      { width: 1440, height: 900 },
    );
    return page;
  },
};

// The gallery: each design opened through the site's own live.html, from a server that lays the
// editor archive at site/editor/ the way the Pages workflow does, so a capture also proves the
// live page boots. Designs the gallery takes from committed fixtures are replayed to .uid first.
const site = join(repo, 'site');
const gallery = JSON.parse(await readFile(join(site, 'gallery.json'), 'utf8'));
for (const item of gallery) {
  if (item.fixture) await writeFile(join(site, item.file), (await design(item.fixture)) + '\n');
}
const siteServer = await listen(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname));
  const file = path.startsWith('/editor/') ? join(root, path.slice('/editor/'.length)) : join(site, path);
  if (!file.startsWith(root) && !file.startsWith(site)) return response.writeHead(403).end();
  try {
    const body = await readFile(file.endsWith('/') ? join(file, 'index.html') : file);
    response.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' }).end(body);
  } catch {
    response.writeHead(404).end();
  }
});
for (const item of gallery) {
  SCENES[`gallery/${item.id}`] = async () => {
    const page = await browser.newPage({
      viewport: { width: 1200, height: 750 },
      deviceScaleFactor: 2,
      locale: 'en-US',
    });
    page.on('pageerror', (error) => console.warn('page error:', String(error)));
    await page.goto(
      `http://127.0.0.1:${siteServer.port}/live.html?design=${item.id}&layout=focused&bare=1`,
    );
    const frame = await (await page.waitForSelector('#app')).contentFrame();
    await frame.waitForFunction(
      () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
      null,
      { timeout: 240_000 },
    );
    await page.waitForTimeout(4_000);
    return page;
  };
}

// For finding click points: `SHOTS=explore EXPLORE='x,y;x,y'` opens the Wear list in the full
// editor and saves explore-<n>.png after each click (points in the editor frame, CSS pixels).
SCENES.explore = async () => {
  const { page, click, settle } = await open(
    { fileName: 'wear-list.uid', text: WEAR_LIST, layout: 'full' },
    { width: 1280, height: 800 },
  );
  const points = (process.env.EXPLORE ?? '').split(';').filter(Boolean);
  for (const [index, point] of points.entries()) {
    const [x, y, button] = point.split(',');
    if (button === 'wheel') {
      await page.mouse.move(Number(x) + (await (await page.$('#app')).boundingBox()).x, Number(y));
      await page.mouse.wheel(0, 600);
    } else {
      await click([Number(x), Number(y)], button ? { button } : undefined);
    }
    await settle(1_200);
    await page.screenshot({ path: join(out, `explore-${index}.png`) });
  }
  return page;
};

let failed = false;
for (const [name, scene] of Object.entries(SCENES)) {
  if (only ? !only.includes(name) : name === 'explore') continue;
  try {
    const page = await scene();
    await mkdir(dirname(join(out, `${name}.png`)), { recursive: true });
    await page.screenshot({ path: join(out, `${name}.png`) });
    await page.close();
    console.log(`ok   ${name}.png`);
  } catch (error) {
    failed = true;
    console.error(`FAIL ${name}: ${error.message.split('\n')[0]}`);
  }
}
await browser.close();
for (const s of [assets, sandbox, hostServer, siteServer]) s.server.close();
process.exit(failed ? 1 : 0);
