#!/usr/bin/env node
// Put a catalog's own designs through its renderer runtime's authoring canvas and fail when the
// canvas cannot draw one of their components: the gate for "the editor shows a red box where the
// export draws the real thing".
//
//   node runtime-coverage.mjs ui-builder/build/wasmDist path/to/runtime-dir designs/*.uid
//
// <runtimeDir> is an unpacked renderer runtime: its runtime-manifest.json names the runtime id.
// Each design is drawn by the editor's sandbox shell (`?rendererRuntimeId=&rendererDocument=`),
// which reads the `undrawn` list the runtime sends beside its `rendered` inspection. Stand-ins are
// listed and pass; unsupported components fail unless named with `--allow <componentId>`.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { basename, extname, join, normalize, resolve } from 'node:path';
import { chromium } from 'playwright';
import { classify, expectUnsupported, parseArgs, report } from './runtime-coverage-lib.mjs';

const options = parseArgs(process.argv.slice(2));
const wasmDist = resolve(options.wasmDist);
const runtimeDir = resolve(options.runtimeDir);
const manifest = JSON.parse(await readFile(join(runtimeDir, 'runtime-manifest.json'), 'utf8'));
const runtimeId = manifest.runtimeId;
const designs = new Map(options.designs.map((path) => [basename(path), resolve(path)]));

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript',
  '.mjs': 'text/javascript',
  '.wasm': 'application/wasm',
  '.json': 'application/json',
  '.uid': 'application/json',
  '.ttf': 'font/ttf',
  '.otf': 'font/otf',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
};

const runtimePrefix = `/ui-builder/runtime/${runtimeId}/`;
// The runtime runs in a sandboxed iframe, whose origin is opaque (`null`): its module scripts, wasm
// and fetches are cross-origin to this server and load only with CORS, as from the real host.
const CORS = { 'access-control-allow-origin': '*' };
const server = createServer(async (request, response) => {
  const path = normalize(decodeURIComponent(new URL(request.url, 'http://x').pathname));
  let file;
  if (path.startsWith(runtimePrefix)) file = join(runtimeDir, path.slice(runtimePrefix.length));
  else if (path.startsWith('/coverage/')) file = designs.get(path.slice('/coverage/'.length));
  else file = join(wasmDist, path === '/' ? 'index.html' : path);
  if (!file || !(file.startsWith(wasmDist) || file.startsWith(runtimeDir) || [...designs.values()].includes(file))) {
    response.writeHead(403, CORS).end();
    return;
  }
  try {
    const body = await readFile(file);
    response
      .writeHead(200, { ...CORS, 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' })
      .end(body);
  } catch {
    response.writeHead(404, CORS).end();
  }
});
await new Promise((ready) => server.listen(0, '127.0.0.1', ready));
const base = `http://127.0.0.1:${server.address().port}`;

const browser = await chromium.launch({
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  ...(process.env.HARNESS_CHROMIUM ? { executablePath: process.env.HARNESS_CHROMIUM } : {}),
});
const results = [];
try {
  for (const name of designs.keys()) {
    const page = await browser.newPage({ locale: 'en-US' });
    const errors = [];
    // The shell throws before the runtime starts when it cannot read a design; fail on that at
    // once rather than waiting out the timeout.
    let failed;
    const pageFailed = new Promise((_, reject) => (failed = reject));
    pageFailed.catch(() => {});
    page.on('pageerror', (error) => {
      errors.push(error.message);
      failed(new Error(`the page threw: ${error.message}`));
    });
    const url =
      `${base}/?rendererRuntimeId=${encodeURIComponent(runtimeId)}` +
      `&rendererDocument=${encodeURIComponent(`/coverage/${name}`)}`;
    try {
      await page.goto(url, { waitUntil: 'load', timeout: 120_000 });
      await Promise.race([
        page.waitForFunction(
          () =>
            document.documentElement.dataset.uiBuilderSandboxReady === 'true' ||
            globalThis.__uiBuilderSandboxLastError !== undefined,
          null,
          { timeout: Number(process.env.COVERAGE_TIMEOUT_MS ?? 120_000) },
        ),
        pageFailed,
      ]);
      const { undrawn, error, measured } = await page.evaluate(() => {
        const inspection = globalThis.__uiBuilderSandboxInspection;
        const key = inspection?.generation?.key;
        return {
          undrawn: key ? globalThis.__uiBuilderUndrawn?.[key] : undefined,
          error: globalThis.__uiBuilderSandboxLastError,
          measured: (inspection?.nodes ?? []).filter((node) => node.bounds).length,
        };
      });
      if (error) {
        results.push({ design: name, ok: false, reason: `the runtime refused it: ${JSON.stringify(error)}`, unsupported: [], standIns: [] });
      } else if (measured === 0) {
        // An empty `undrawn` list from a canvas that laid nothing out is not a canvas that drew
        // everything: a runtime answering on its device player reports no bounds and no gaps.
        results.push({ design: name, ok: false, reason: 'the runtime measured no node, so drew nothing to check', unsupported: [], standIns: [] });
      } else {
        results.push(classify(name, undrawn, options.allow));
      }
    } catch (error) {
      const others = errors.filter((it) => !error.message.includes(it));
      results.push({ design: name, ok: false, reason: `${error.message}${others.length ? `; ${others.join('; ')}` : ''}`, unsupported: [], standIns: [] });
    } finally {
      await page.close();
    }
  }
} finally {
  await browser.close();
  server.close();
}

console.log(report(results));
if (options.expect.size > 0) {
  // A negative control: these designs must fail, and only for the expected components.
  const problems = expectUnsupported(results, options.expect);
  for (const problem of problems) console.log(`FAIL ${problem}`);
  console.log(
    problems.length === 0
      ? `${runtimeId} reported exactly ${[...options.expect].join(', ')} as unsupported`
      : `${runtimeId} did not report the expected unsupported components`,
  );
  process.exit(problems.length === 0 ? 0 : 1);
}
const failed = results.filter((it) => !it.ok).length;
console.log(`${results.length - failed}/${results.length} designs drawn by ${runtimeId}`);
process.exit(failed === 0 ? 0 : 1);
