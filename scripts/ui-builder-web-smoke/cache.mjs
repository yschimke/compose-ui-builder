// A fresh browser process for every visit, one retained disk profile. HTTP byte-cache hits alone
// would have passed with the old boot script; require Chromium's compiled-Wasm cache hits too.
import assert from 'node:assert/strict';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { chromium } from 'playwright';

export async function verifyCompiledWasmCache(base, launchOptions, waitForReady, output) {
  const profile = await mkdtemp(join(tmpdir(), 'ui-builder-cache-'));
  const samples = [];
  try {
    for (let visit = 0; visit < 3; visit++) {
      const context = await chromium.launchPersistentContext(profile, {
        ...launchOptions,
        // This lane tests cache transport, not V8's workload-dependent write debounce. Chrome 141
        // can keep postponing Skiko's write as more functions tier up after first paint. Disable
        // that debounce only here; normal desktop/mobile startup uses the browser defaults.
        args: [...(launchOptions.args ?? []), '--js-flags=--wasm-caching-timeout-ms=0'],
        locale: 'en-US', viewport: { width: 1400, height: 900 },
      });
      try {
        const page = context.pages()[0];
        const errors = [];
        page.on('pageerror', (error) => errors.push(String(error)));
        const cdp = await context.newCDPSession(page);
        const { product, jsVersion } = await cdp.send('Browser.getVersion');
        const events = [];
        cdp.on('Tracing.dataCollected', ({ value }) => {
          events.push(...value.filter((event) => event.name.startsWith('v8.wasm.')));
        });
        await cdp.send('Tracing.start', {
          categories: 'disabled-by-default-devtools.timeline', transferMode: 'ReportEvents',
        });
        await page.goto(`${base}/index.html?mode=interactive-editor`);
        await waitForReady(page);
        // Tiering and code-cache writes happen after the first frame. This is a cache correctness
        // test, not an absolute latency gate; give background writes time before closing Chrome.
        await page.waitForTimeout(3000);
        const sample = await page.evaluate(() => ({
          startup: globalThis.__uiBuilderStartup,
          wasm: performance.getEntriesByType('resource')
            .filter((entry) => /\.wasm(?:[?#]|$)/.test(entry.name))
            .map((entry) => ({
              file: new URL(entry.name).pathname.split('/').pop(),
              transferSize: entry.transferSize, decodedBodySize: entry.decodedBodySize,
            })),
        }));
        const ended = new Promise((resolve) => cdp.once('Tracing.tracingComplete', resolve));
        await cdp.send('Tracing.end');
        await ended;
        const hits = events.filter((event) => event.name === 'v8.wasm.moduleCacheHit');
        const cachedFiles = hits.map((event) => new URL(event.args.url).pathname.split('/').pop());
        samples.push({ visit, browser: { product, jsVersion }, cacheWriteDebounceMs: 0, ...sample, cachedFiles, errors });
        if (output) {
          await writeFile(join(output, `wasm-cache-${visit}.json`), JSON.stringify(events, null, 2));
        }
        assert.deepEqual(errors, [], 'the cached editor must start without page errors');
        if (visit > 0) {
          for (const file of ['uiBuilder.wasm', 'skiko.wasm']) {
            const resource = sample.wasm.find((entry) => entry.file === file);
            assert.ok(resource?.decodedBodySize > 0, `${file} was not loaded`);
            assert.equal(resource.transferSize, 0, `${file} missed the HTTP cache`);
            assert.ok(cachedFiles.includes(file), `${file} missed the compiled-Wasm cache`);
          }
        }
        console.log(`ok   cache visit=${visit}: compiled hits=${cachedFiles.join(', ') || 'cold'}`);
      } finally {
        await context.close();
      }
    }
  } finally {
    if (output) await writeFile(join(output, 'wasm-cache.json'), JSON.stringify(samples, null, 2));
    await rm(profile, { recursive: true, force: true });
  }
}
