#!/usr/bin/env node
// Measures the browser memory the Wasm editor holds on its main screens: the `/ui-builder/designs`
// home screen, and one open design per catalog (Material 3, Wear M3, RemoteCompose widgets, A2UI).
//
//   node scripts/ui-builder-web-smoke/memory.mjs ui-builder/build/wasmDist [out-dir]
//
// The page talks to a stand-in host here, not compose-preview-server: identity, catalogs, the
// design list and `openDesign` snapshots of committed `.uid` designs, which is everything those
// screens read on startup. Each scenario is a fresh Chromium, so one screen's garbage is never
// charged to the next. Environment: MEMORY_RUNS (default 3), MEMORY_SETTLE_MS (default 8000),
// MEMORY_VIEWPORT (`desktop` or `mobile`, default both), MEMORY_DEBUG (page console, progress),
// HARNESS_CHROMIUM. Write the output to a file, not a pipe: a pipe holds it until the end.

import { createServer } from 'node:http';
import { mkdir, readFile, stat, writeFile } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { chromium } from 'playwright';

const dist = resolve(process.argv[2] ?? 'ui-builder/build/wasmDist');
const out = resolve(process.argv[3] ?? 'build/web-memory');
const repo = resolve(new URL('../..', import.meta.url).pathname);
const runs = Number(process.env.MEMORY_RUNS ?? 3);
const settleMs = Number(process.env.MEMORY_SETTLE_MS ?? 8000);
const fixtures = join(repo, 'docs/design/fixtures/ui-builder');
const debug = process.env.MEMORY_DEBUG ? (...a) => console.log('debug:', ...a) : () => {};

const readJson = async (path) => JSON.parse(await readFile(path, 'utf8'));
const catalogFiles = {
  'm3-catalog': 'm3-catalog-capabilities-v1.json',
  'wear-m3': 'wear-m3-capabilities-v1.json',
  'remote-m3': 'remote-m3-capabilities-v1.json',
  'a2ui-catalog': 'a2ui-catalog-capabilities-v1.json',
};
const catalogs = {};
for (const [id, file] of Object.entries(catalogFiles)) catalogs[id] = await readJson(join(fixtures, file));

// The A2UI catalog has no committed design; this is its New design starter
// (`a2uiUiBuilderDocument`) with a card, a field and a button added, so it draws more than a line.
function a2uiDocument() {
  const literal = (type, value) => ({ type, value });
  const node = (id, componentId, properties = {}, slots = {}) => ({
    id, componentId, properties, modifiers: [], slots, eventBindings: {}, predicate: null,
    accessibility: null, assetBindings: {}, tokenBindings: {}, component: null,
  });
  const nodes = [
    node('a2ui-column', 'a2ui/Column', {}, { children: ['a2ui-headline', 'a2ui-card'] }),
    node('a2ui-headline', 'a2ui/Text', { text: literal('string', 'Hello, A2UI'), variant: literal('enum', 'h2') }),
    node('a2ui-card', 'a2ui/Card', {}, { child: ['a2ui-card-column'] }),
    node('a2ui-card-column', 'a2ui/Column', {}, { children: ['a2ui-body', 'a2ui-field', 'a2ui-divider', 'a2ui-button'] }),
    node('a2ui-body', 'a2ui/Text', { text: literal('string', 'Book a table for tonight.'), variant: literal('enum', 'body') }),
    node('a2ui-field', 'a2ui/TextField', { label: literal('string', 'Name') }),
    node('a2ui-divider', 'a2ui/Divider'),
    node('a2ui-button', 'a2ui/Button', {}, { child: ['a2ui-button-label'] }),
    node('a2ui-button-label', 'a2ui/Text', { text: literal('string', 'Reserve') }),
  ];
  return {
    schema: 'compose-ui-builder-document/v1-candidate', id: 'a2ui-reservation', title: 'A2UI reservation',
    revision: 0, catalogPin: {}, environment: {
      widthDp: 412, heightDp: 915, density: 2.625, theme: 'light', dynamicColor: false, locale: 'en-US',
      fontScale: 1.0, layoutDirection: 'ltr', windowPosture: 'flat', browserZoomPercent: 100,
      fixedTime: '2024-05-16T12:00:00Z', animations: 'settled', networkAccess: false, background: null,
      typeface: null, exportDevices: [],
    },
    stateVariables: {}, roots: ['a2ui-column'], nodes: Object.fromEntries(nodes.map((n) => [n.id, n])),
    assets: {}, tokenBindings: {}, createdAtEpochMillis: null, updatedAtEpochMillis: null, components: {},
  };
}

// One open design per catalog, plus a second Wear design so the list is not one-per-row.
const designs = {};
const pinTo = (document, systemId) => {
  const benchmark = catalogs[systemId].benchmark;
  document.catalogPin = {
    systemId, catalogRevision: benchmark.catalogRevision, capabilityDigest: 'candidate',
    nativeRuntimeId: benchmark.nativeRuntimeId,
  };
  designs[document.id] = document;
  return document.id;
};
const scenarioDesigns = {
  'm3-catalog': pinTo(await readJson(join(repo, 'site/designs/google-gmail-tablet.uid')), 'm3-catalog'),
  'wear-m3': pinTo(await readJson(join(repo, 'site/designs/google-home-wear.uid')), 'wear-m3'),
  'remote-m3': pinTo(await readJson(join(repo, 'site/designs/weather-widget.uid')), 'remote-m3'),
  'a2ui-catalog': pinTo(a2uiDocument(), 'a2ui-catalog'),
};
pinTo(await readJson(join(repo, 'site/designs/wear-list.uid')), 'wear-m3');
const devicePresets = await readFile(join(repo, 'site/device-presets.json'));

const types = {
  '.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.wasm': 'application/wasm',
  '.json': 'application/json', '.ttf': 'font/ttf', '.txt': 'text/plain', '.png': 'image/png',
};
const unknownRequests = new Map();
const note = (key) => unknownRequests.set(key, (unknownRequests.get(key) ?? 0) + 1);

function respondJson(response, body, status = 200) {
  response.writeHead(status, { 'content-type': 'application/json' });
  response.end(JSON.stringify(body));
}

function serviceResponse(request) {
  const now = Date.UTC(2026, 9, 6);
  switch (request.type) {
    case 'listCatalogs':
      return { type: 'catalogs', catalogs: Object.values(catalogs) };
    case 'listDesigns':
      return {
        type: 'designs', nextCursor: null,
        designs: Object.values(designs).map((d, index) => ({
          designId: d.id, title: d.title, revision: 1, accessRevision: 0, catalogPin: d.catalogPin,
          createdAtEpochMillis: now - 86_400_000 * (index + 1), updatedAtEpochMillis: now - 3_600_000 * (index + 1),
          ownerActorId: 'github:memory-probe',
          requesterAccess: {
            actorId: 'github:memory-probe', role: 'owner',
            allowedActions: ['read', 'write', 'export', 'manageAccess', 'delete'],
          },
        })),
      };
    case 'openDesign':
    case 'getSnapshot': {
      const document = designs[request.designId];
      if (!document) return { type: 'error', error: { code: 'notFound', message: `no design ${request.designId}` } };
      return {
        type: 'snapshot', snapshot: {
          designId: document.id, state: { lastSequence: 1, document: { ...document, revision: 1 } },
          catalog: catalogs[document.catalogPin.systemId], retainedFromSequence: 0,
        },
      };
    }
    case 'updatePresence':
      return { type: 'presenceAccepted', designId: request.designId, actorId: 'github:memory-probe' };
    default:
      note(`request ${request.type}`);
      return { type: 'error', error: { code: 'notFound', message: `not served by the memory harness: ${request.type}` } };
  }
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, 'http://localhost');
  const path = url.pathname;
  try {
    if (path === '/api/ui-builder/v1/identity') {
      return respondJson(response, {
        schemaVersion: 1, actorId: 'github:memory-probe', signedIn: true, canWrite: true,
        designVisibilitySupported: false,
      });
    }
    if (path === '/api/ui-builder/v1/device-presets') {
      response.writeHead(200, { 'content-type': 'application/json' });
      return response.end(devicePresets);
    }
    if (path === '/api/ui-builder/v1/requests' && request.method === 'POST') {
      const chunks = [];
      for await (const chunk of request) chunks.push(chunk);
      const { requestId, request: body } = JSON.parse(Buffer.concat(chunks).toString('utf8'));
      return respondJson(response, { schemaVersion: 1, requestId, response: serviceResponse(body) });
    }
    if (path.startsWith('/api/')) {
      note(`${request.method} ${path.replace(/designs\/[^/]+/, 'designs/{id}')}`);
      response.writeHead(404);
      return response.end();
    }
    if (!path.startsWith('/ui-builder/')) {
      note(`${request.method} ${path}`);
      response.writeHead(404);
      return response.end();
    }
    const relative = normalize(decodeURIComponent(path.slice('/ui-builder/'.length)));
    if (relative.startsWith('..')) throw new Error('outside the bundle');
    let file = join(dist, relative);
    // `/ui-builder/designs` and `/ui-builder/<designId>` are the shell, as the host serves them.
    if (!relative || !(await stat(file).then((s) => s.isFile(), () => false))) file = join(dist, 'index.html');
    response.writeHead(200, {
      'content-type': types[extname(file)] ?? 'application/octet-stream',
      'cache-control': 'no-cache',
    });
    response.end(await readFile(file));
  } catch (error) {
    response.writeHead(500);
    response.end(String(error));
  }
});
await new Promise((ready) => server.listen(0, '127.0.0.1', ready));
const base = `http://127.0.0.1:${server.address().port}`;
debug('serving', base);

const viewports = {
  desktop: { viewport: { width: 1400, height: 900 } },
  mobile: { viewport: { width: 412, height: 915 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true },
};
const scenarios = [
  { id: 'designs', label: 'Designs (home)', path: '/ui-builder/designs' },
  ...Object.entries(scenarioDesigns).map(([catalog, designId]) => ({
    id: `open-${catalog}`, label: `Open design · ${catalog}`, path: `/ui-builder/${designId}`, catalog, designId,
  })),
];

async function procMemory(pid) {
  try {
    const rollup = await readFile(`/proc/${pid}/smaps_rollup`, 'utf8');
    const field = (name) => Number(rollup.match(new RegExp(`^${name}:\\s+(\\d+) kB`, 'm'))?.[1] ?? 0) * 1024;
    return { rss: field('Rss'), pss: field('Pss') };
  } catch {
    return { rss: 0, pss: 0 };
  }
}

async function processBreakdown(browser) {
  const session = await browser.newBrowserCDPSession();
  try {
    const { processInfo } = await session.send('SystemInfo.getProcessInfo');
    const byType = {};
    for (const { type, id } of processInfo) {
      const memory = await procMemory(id);
      const entry = (byType[type] ??= { count: 0, rss: 0, pss: 0 });
      entry.count++;
      entry.rss += memory.rss;
      entry.pss += memory.pss;
    }
    return byType;
  } finally {
    await session.detach();
  }
}

async function sample(page, cdp, browser) {
  await cdp.send('HeapProfiler.collectGarbage');
  await new Promise((r) => setTimeout(r, 500));
  const { metrics } = await cdp.send('Performance.getMetrics');
  const metric = Object.fromEntries(metrics.map(({ name, value }) => [name, value]));
  const dom = await cdp.send('Memory.getDOMCounters');
  const page_ = await page.evaluate(async () => {
    // Canvases are where Skiko keeps its GPU-backed surfaces; their backing store is GPU memory.
    // Compose mounts its canvas inside a shadow root, so a plain querySelectorAll finds nothing.
    const found = [];
    const walk = (root) => {
      for (const element of root.querySelectorAll('*')) {
        if (element instanceof HTMLCanvasElement) found.push(element);
        if (element.shadowRoot) walk(element.shadowRoot);
      }
    };
    walk(document);
    const canvases = found.map((c) => c.width * c.height * 4);
    let uaMemory = null;
    if (globalThis.crossOriginIsolated && performance.measureUserAgentSpecificMemory) {
      try {
        uaMemory = (await performance.measureUserAgentSpecificMemory()).bytes;
      } catch { /* unavailable on this build */ }
    }
    return { canvasBackingBytes: canvases.reduce((a, b) => a + b, 0), canvasCount: canvases.length, uaMemory };
  });
  const processes = await processBreakdown(browser);
  const total = (key) => Object.values(processes).reduce((sum, p) => sum + p[key], 0);
  return {
    jsHeapUsed: metric.JSHeapUsedSize, jsHeapTotal: metric.JSHeapTotalSize,
    domNodes: dom.nodes, jsEventListeners: dom.jsEventListeners, documents: dom.documents,
    ...page_,
    rendererPss: processes.renderer?.pss ?? 0, gpuPss: processes['GPU']?.pss ?? processes.gpu?.pss ?? 0,
    browserPss: processes.browser?.pss ?? 0, totalPss: total('pss'), totalRss: total('rss'),
    processes,
  };
}

async function measure(scenario, viewportName) {
  const browser = await chromium.launch({
    args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
    ...(process.env.HARNESS_CHROMIUM ? { executablePath: process.env.HARNESS_CHROMIUM } : {}),
  });
  try {
    const context = await browser.newContext({ ...viewports[viewportName], locale: 'en-US' });
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', (error) => errors.push(String(error)));
    const cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable');
    const started = Date.now();
    page.on('console', (message) => debug(`console ${message.type()}: ${message.text()}`));
    debug('goto', scenario.path);
    await page.goto(`${base}${scenario.path}`);
    debug('loaded');
    await page.waitForFunction(() => document.documentElement.dataset.uiBuilderReady === 'true', null, { timeout: 120_000 });
    await page.waitForFunction(() => globalThis.__uiBuilderStartup?.marks?.['editor-paint-opportunity'] !== undefined, null, { timeout: 60_000 });
    const readyMs = Date.now() - started;
    debug('ready', readyMs);
    const atReady = await sample(page, cdp, browser);
    await page.waitForTimeout(settleMs);
    const settled = await sample(page, cdp, browser);
    const status = await page.evaluate(() => ({ ...document.documentElement.dataset }));
    await page.screenshot({ path: join(out, `${scenario.id}-${viewportName}.png`) });
    return { readyMs, atReady, settled, errors, status };
  } finally {
    await browser.close();
  }
}

// One blank page in a fresh Chromium: the floor every scenario sits on.
async function baseline(viewportName) {
  const browser = await chromium.launch({
    args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
    ...(process.env.HARNESS_CHROMIUM ? { executablePath: process.env.HARNESS_CHROMIUM } : {}),
  });
  try {
    const context = await browser.newContext({ ...viewports[viewportName], locale: 'en-US' });
    const page = await context.newPage();
    const cdp = await context.newCDPSession(page);
    await cdp.send('Performance.enable');
    await page.goto('about:blank');
    await page.waitForTimeout(1000);
    return await sample(page, cdp, browser);
  } finally {
    await browser.close();
  }
}

const median = (values) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.floor(sorted.length / 2)];
};
const keys = ['jsHeapUsed', 'jsHeapTotal', 'rendererPss', 'gpuPss', 'browserPss', 'totalPss', 'totalRss', 'domNodes', 'canvasBackingBytes'];
const summarise = (samples) => Object.fromEntries(keys.map((k) => [k, median(samples.map((s) => s[k]))]));
const mb = (bytes) => (bytes / 1024 / 1024).toFixed(1);

await mkdir(out, { recursive: true });
const chosen = process.env.MEMORY_VIEWPORT ? process.env.MEMORY_VIEWPORT.split(',') : Object.keys(viewports);
const report = {
  measuredAt: new Date().toISOString(), runs, settleMs, chromium: chromium.executablePath(), results: [],
};
for (const viewportName of chosen) {
  const blank = [];
  debug('baseline start');
  for (let i = 0; i < runs; i++) blank.push(await baseline(viewportName));
  debug('baseline done');
  report.results.push({ scenario: 'about-blank', label: 'about:blank (floor)', viewport: viewportName, settled: summarise(blank), samples: blank });
  for (const scenario of scenarios) {
    const samples = [];
    for (let i = 0; i < runs; i++) {
      const result = await measure(scenario, viewportName);
      samples.push(result);
      console.log(
        `${viewportName.padEnd(7)} ${scenario.id.padEnd(18)} run ${i + 1}: ready ${result.readyMs} ms, ` +
          `JS heap ${mb(result.settled.jsHeapUsed)} MB, renderer PSS ${mb(result.settled.rendererPss)} MB, ` +
          `GPU PSS ${mb(result.settled.gpuPss)} MB, total PSS ${mb(result.settled.totalPss)} MB` +
          (result.errors.length ? `, ${result.errors.length} page errors` : ''),
      );
    }
    report.results.push({
      scenario: scenario.id, label: scenario.label, path: scenario.path, viewport: viewportName,
      readyMs: median(samples.map((s) => s.readyMs)),
      atReady: summarise(samples.map((s) => s.atReady)),
      settled: summarise(samples.map((s) => s.settled)),
      errors: [...new Set(samples.flatMap((s) => s.errors))],
      samples,
    });
  }
}
report.unservedRequests = Object.fromEntries(unknownRequests);
await writeFile(join(out, 'memory.json'), JSON.stringify(report, null, 2));

console.log('\nMedian after settling (MB):');
console.log('viewport scenario            JS heap  renderer  GPU    browser  total PSS  DOM nodes');
for (const r of report.results) {
  const s = r.settled;
  console.log(
    `${r.viewport.padEnd(8)} ${r.scenario.padEnd(20)} ${mb(s.jsHeapUsed).padStart(7)} ${mb(s.rendererPss).padStart(9)} ` +
      `${mb(s.gpuPss).padStart(6)} ${mb(s.browserPss).padStart(8)} ${mb(s.totalPss).padStart(10)} ${String(s.domNodes).padStart(10)}`,
  );
}
console.log(`\nUnserved requests: ${JSON.stringify(report.unservedRequests)}`);
console.log(`Report: ${join(out, 'memory.json')}`);
server.close();
