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

import { createServer } from 'node:http';
import { readFile, mkdir } from 'node:fs/promises';
import { extname, join, normalize, resolve } from 'node:path';
import { chromium } from 'playwright';

const root = resolve(process.argv[2] ?? 'ui-builder/build/wasmDist');
const shots = process.argv[3] ? resolve(process.argv[3]) : null;

// The modes that render from files in the distribution alone.
const MODES = ['mode=reference', 'mode=interactive-editor'];

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

// SwiftShader gives headless Chromium the WebGL context Skiko needs on a GPU-less runner.
const browser = await chromium.launch({
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
if (shots) await mkdir(shots, { recursive: true });

let failures = 0;
for (const mode of MODES) {
  // An explicit locale: without one the page's `Intl` calls throw on a runner with none set.
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 }, locale: 'en-US' });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() === 'error' && !IGNORED_CONSOLE.some((it) => it.test(message.text()))) {
      errors.push(message.text());
    }
  });
  let ready = false;
  try {
    await page.goto(`${base}/index.html?${mode}`);
    await page.waitForFunction(
      () => document.documentElement.getAttribute('data-ui-builder-ready') === 'true',
      null,
      { timeout: Number(process.env.SMOKE_READY_TIMEOUT_MS ?? 120_000) },
    );
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
  if (shots) await page.screenshot({ path: join(shots, `${mode.replace(/\W+/g, '-')}.png`) });
  const ok = ready && errors.length === 0;
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${mode}${errors.length ? `\n  ${errors.join('\n  ')}` : ''}`);
  if (!ok) failures++;
  await page.close();
}

await browser.close();
server.close();
process.exit(failures === 0 ? 0 : 1);
