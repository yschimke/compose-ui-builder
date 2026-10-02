// Loads the built Wasm editor in a real browser and fails if it does not come up.
//
// Everything else in this repository's CI proves the editor on the JVM. The browser that ships it
// — Skiko on WebGL, the Wasm module, the page's own bridges — was only exercised by
// compose-preview-server's Playwright harness, so a change here that broke the page went red in
// the other repository or nowhere. This is the smallest check that closes that: serve the
// `wasmFrontendDist` output as it ships, open each capture mode that needs no server, and require
// the page to signal ready (`data-ui-builder-ready`) with no page error.
//
// Usage: node smoke.mjs <wasmDist directory> [screenshot directory]
//
// Lanes, all by default; pick some with `SMOKE_LANES=mobile,offline` (comma-separated):
// - `desktop`: the capture modes at 1400×900, as a desktop browser opens them.
// - `mobile`:  the interactive editor as a phone opens it — 412×915 CSS px at a 2.625 device pixel
//              ratio (a Pixel 7-class Android phone), touch, `isMobile` — so the compact layout,
//              the viewport meta and the safe-area/keyboard measurement all run.
// - `offline`: the phone again with `?offline=1`, which registers the service worker; once it
//              controls the page, a reload with the network cut must still come up ready.

import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { chromium } from 'playwright';

const root = resolve(process.argv[2] ?? 'ui-builder/build/wasmDist');
const shots = process.argv[3] ? resolve(process.argv[3]) : null;

const DESKTOP = { viewport: { width: 1400, height: 900 } };
// A Pixel 7-class phone, as Chrome's device emulation describes one.
const PHONE = {
  viewport: { width: 412, height: 915 },
  deviceScaleFactor: 2.625,
  isMobile: true,
  hasTouch: true,
};

// The modes that render from files in the distribution alone, under a well-formed locale.
const RUNS = [
  { lane: 'desktop', mode: 'mode=reference', languages: null, device: DESKTOP },
  { lane: 'desktop', mode: 'mode=interactive-editor', languages: null, device: DESKTOP },
  // What Chromium reports when it starts under `LANG=C.UTF-8`, as in an agent sandbox: a tag that is
  // not BCP 47, which Compose's `Intl.Locale` call used to reject before the first frame. Forced
  // through an init script so the run does not depend on the runner's own locale.
  { lane: 'desktop', mode: 'mode=interactive-editor', languages: ['en-US@posix'], device: DESKTOP },
  { lane: 'mobile', mode: 'mode=interactive-editor', languages: null, device: PHONE },
  { lane: 'offline', mode: 'mode=interactive-editor&offline=1', languages: null, device: PHONE },
];
const LANES = (process.env.SMOKE_LANES ?? 'desktop,mobile,offline').split(',').map((it) => it.trim());
const unknown = LANES.filter((lane) => !RUNS.some((run) => run.lane === lane));
if (unknown.length) {
  console.error(`unknown SMOKE_LANES ${unknown.join(', ')}; known: desktop, mobile, offline`);
  process.exit(2);
}

// Console output that is not a failure: a deprecation notice the Kotlin/Wasm runtime prints.
const IGNORED_CONSOLE = [/Accessing `memory` via `wasmExports` is deprecated/];

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.wasm': 'application/wasm',
  '.json': 'application/json',
  '.map': 'application/json',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

const server = createServer(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname));
  const file = join(root, path === '/' ? 'index.html' : path);
  if (!file.startsWith(root)) {
    response.writeHead(403).end();
    return;
  }
  try {
    const body = await readFile(file);
    response
      .writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' })
      .end(body);
  } catch {
    response.writeHead(404).end();
  }
});
await new Promise((ready) => server.listen(0, '127.0.0.1', ready));
const base = `http://127.0.0.1:${server.address().port}`;

const waitForReady = (page) =>
  page.waitForFunction(
    () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
    null,
    { timeout: Number(process.env.SMOKE_READY_TIMEOUT_MS ?? 120_000) },
  );

// SwiftShader gives headless Chromium the WebGL context Skiko needs on a GPU-less runner.
const browser = await chromium.launch({
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
if (shots) await mkdir(shots, { recursive: true });

let failures = 0;
for (const { lane, mode, languages, device } of RUNS.filter((run) => LANES.includes(run.lane))) {
  const label = `${lane} ${mode}${languages ? ` languages=${languages.join(',')}` : ''}`;
  // An explicit locale, so every run but the forced one is independent of the runner's. A context
  // per run, so one lane's service worker and caches never reach another's.
  const context = await browser.newContext({ ...device, locale: 'en-US' });
  if (languages) {
    await context.addInitScript((tags) => {
      Object.defineProperty(navigator, 'languages', { configurable: true, get: () => tags });
      Object.defineProperty(navigator, 'language', { configurable: true, get: () => tags[0] });
    }, languages);
  }
  const errors = [];
  const watch = (page) => {
    page.on('pageerror', (error) => errors.push(String(error)));
    page.on('console', (message) => {
      if (message.type() === 'error' && !IGNORED_CONSOLE.some((it) => it.test(message.text()))) {
        const where = message.location()?.url;
        errors.push(where ? `${message.text()} (${where})` : message.text());
      }
    });
    return page;
  };
  let page = watch(await context.newPage());
  let ready = false;
  try {
    await page.goto(`${base}/index.html?${mode}`);
    await waitForReady(page);
    if (lane === 'offline') {
      // Installed means precached: the worker's install step fetches the shell and the bundle.
      await page.waitForFunction(
        () => navigator.serviceWorker?.controller != null,
        null,
        { timeout: Number(process.env.SMOKE_READY_TIMEOUT_MS ?? 120_000) },
      );
      // Straight to offline, in a fresh tab: everything the first, uncontrolled load fetched has
      // to have been precached, or this cold start is missing it.
      await page.close();
      await context.setOffline(true);
      page = watch(await context.newPage());
      await page.goto(`${base}/index.html?${mode}`);
      await waitForReady(page);
      const controlled = await page.evaluate(() => navigator.serviceWorker.controller != null);
      if (!controlled) errors.push('the offline reload was not served by the service worker');
    }
    ready = true;
    // The boot screen covers the whole page, so it has to be gone by the time the page says it is
    // ready — not shortly after. Anything that captures at ready (compose-preview-server's visual
    // harness does) would otherwise photograph it.
    if (await page.evaluate(() => document.getElementById('ui-builder-boot') !== null)) {
      errors.push('the boot screen was still up when the page reported ready');
    }
  } catch (error) {
    errors.push(`not ready: ${error.message.split('\n')[0]}`);
  }
  if (shots) await page.screenshot({ path: join(shots, `${label.replace(/\W+/g, '-')}.png`) });
  const ok = ready && errors.length === 0;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}${errors.length ? `\n  ${errors.join('\n  ')}` : ''}`);
  if (!ok) failures++;
  await context.close();
}

await browser.close();
server.close();
process.exit(failures === 0 ? 0 : 1);
