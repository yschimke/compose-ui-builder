#!/usr/bin/env node
// Screenshot the deployed browser editor on a hosted design, as an agent holding a grant sees it.
//
//   UI_BUILDER_TOKEN=$(compose-preview auth token --server https://ui.coo.ee) \
//     node editor-shot.mjs https://ui.coo.ee/<design-id> editor.png
//
// The grant (`compose-preview auth request --server … --capability ui-builder-read`) is sent as
// `Authorization: Bearer` on every request to the design's origin, so a private design opens as it
// does for the person who approved it, with the editor's own canvas, panels and device previews:
// what `ui_builder_view` cannot show, since that draws the export, not the editor.
//
// Options (after the two positionals):
//   --document FILE   Replace the design's document in the host's replies with FILE (a
//                     DesignDocumentV1, e.g. a past revision from `ui_builder_get_design`), so the
//                     editor draws a revision it would not otherwise open. Nothing is written back.
//   --viewport WxH    Default 1400x900.
//   --settle MS       Wait after the editor is ready, for previews to draw. Default 10000.
//   --steps JSON      Run before the final screenshot: [["click",x,y],["wait",ms],["key","Escape"],
//                     ["shot","file.png"]], coordinates in CSS pixels of the viewport.
import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';
import {
  catalogPinKey,
  isDesignReply,
  needsGrant,
  parseArgs,
  replaceDocument,
} from './editor-shot-lib.mjs';

const options = parseArgs(process.argv.slice(2));
const token = process.env.UI_BUILDER_TOKEN || null;
const origin = new URL(options.url).origin;
const replacement = options.document ? JSON.parse(readFileSync(options.document, 'utf8')) : null;

const proxy = process.env.HTTPS_PROXY
  ? { server: process.env.HTTPS_PROXY, bypass: '127.0.0.1,localhost' }
  : undefined;
const browser = await chromium.launch({
  proxy,
  executablePath: process.env.HARNESS_CHROMIUM || undefined,
  // Skiko draws through WebGL; a sandbox has no GPU, so use SwiftShader.
  args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
});
try {
  // `locale`: under LANG=C.UTF-8 Chromium reports `en-US@posix`, which Intl.Locale rejects.
  const context = await browser.newContext({ viewport: options.viewport, locale: 'en-US' });
  let replaced = 0;
  const hostPins = new Set();
  await context.route(
    (url) => url.origin === origin,
    async (route) => {
      const url = route.request().url();
      const authorize = token !== null && needsGrant(url);
      const swap = replacement !== null && isDesignReply(url);
      if (!authorize && !swap) return route.continue();
      const headers = { ...route.request().headers() };
      if (authorize) headers.authorization = `Bearer ${token}`;
      // Playwright applies these headers to every redirect in the chain, whatever its origin. Take
      // the first hop only and hand a redirect back to the browser, which routes its target again:
      // the grant goes with it only if that target is this origin's API too.
      const response = await route.fetch({ headers, maxRedirects: 0 });
      if (!swap || !(response.headers()['content-type'] ?? '').includes('json')) {
        return route.fulfill({ response });
      }
      const body = await response.json();
      const result = replaceDocument(body, replacement);
      replaced += result.replaced;
      result.hostPins.forEach((pin) => hostPins.add(pin));
      return route.fulfill({ response, json: body });
    },
  );
  const page = await context.newPage();
  page.on('pageerror', (error) => console.error(`pageerror: ${error.message}`));
  page.on('response', (response) => {
    if (response.status() === 401 || response.status() === 403) {
      console.error(`${response.status()} ${response.url()}`);
    }
  });
  await page.goto(options.url, { waitUntil: 'load', timeout: 120_000 });
  await page.waitForFunction(
    () => document.documentElement.hasAttribute('data-ui-builder-ready'),
    null,
    { timeout: 120_000 },
  );
  await page.waitForTimeout(options.settle);
  // What the editor's canvases said they did not draw as themselves: the text form of a red box,
  // which a screenshot alone leaves for someone to notice.
  const undrawn = await page.evaluate(() => globalThis.__uiBuilderUndrawn ?? null);
  if (undrawn === null) {
    console.log('undrawn: not reported (an editor that predates the report)');
  } else {
    for (const [key, nodes] of Object.entries(undrawn)) {
      if (nodes.length === 0) continue;
      const list = nodes.map((it) => `${it.nodeId} (${it.componentId}, ${it.reason})`).join(', ');
      console.log(`undrawn in ${key}: ${list}`);
    }
  }
  if (replacement) {
    console.log(replaced > 0 ? `replaced the document ${replaced}×` : 'the document was never sent');
    // The editor draws a swapped revision against the catalog the host sent with the current one.
    const pin = catalogPinKey(replacement);
    const others = [...hostPins].filter((it) => it !== pin);
    if (others.length > 0) {
      console.error(
        `warning: --document is pinned to ${pin} but the host sent ${others.join(', ')}; ` +
          'the editor draws it against the host catalog, so components that changed between ' +
          'them may draw differently or as unsupported',
      );
    }
  }
  for (const [op, a, b] of options.steps) {
    if (op === 'click') await page.mouse.click(a, b);
    else if (op === 'wait') await page.waitForTimeout(a);
    else if (op === 'key') await page.keyboard.press(a);
    else if (op === 'shot') await page.screenshot({ path: a });
    else throw new Error(`unknown step ${op}`);
  }
  await page.screenshot({ path: options.output });
  console.log(options.output);
} finally {
  await browser.close();
}
