import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, runInContext } from 'node:vm';
import { test } from 'node:test';

const source = await readFile(new URL('../../ui-builder/src/wasmJsMain/resources/ui-builder-boot.js', import.meta.url), 'utf8');

function bootPage({ withBoot = true } = {}) {
  const fields = new Map();
  const boot = { dataset: {}, querySelector: (name) => {
    if (!fields.has(name)) fields.set(name, { textContent: '' });
    return fields.get(name);
  } };
  const events = new Map();
  const intervals = new Set();
  const performanceMarks = [];
  let now = 0;
  const fetch = async () => new Response(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0]), {
    headers: { 'content-type': 'application/wasm' },
  });
  const context = createContext({
    document: { getElementById: () => withBoot ? boot : null },
    navigator: {},
    fetch,
    Response,
    ReadableStream,
    performance: { now: () => ++now, mark: (name) => performanceMarks.push(name) },
    setInterval: (callback) => { intervals.add(callback); return callback; },
    clearInterval: (callback) => intervals.delete(callback),
    addEventListener: (name, handler) => events.set(name, handler),
  });
  context.window = context;
  runInContext(source, context);
  return { context, fields, boot, events, intervals, performanceMarks, fetch };
}

test('Wasm fetch remains untouched throughout boot, including failure and dismissal', async () => {
  const page = bootPage();
  assert.equal(page.context.fetch, page.fetch);
  const response = await page.context.fetch('/uiBuilder.wasm');
  const { instance } = await WebAssembly.instantiateStreaming(response);
  assert.ok(instance instanceof WebAssembly.Instance);
  page.context.composeUiBuilderBoot.phase('Opening the design');
  page.events.get('unhandledrejection')({ reason: new Error('load failed') });
  assert.equal(page.boot.dataset.state, 'failed');
  assert.equal(page.fields.get('[data-boot-detail]').textContent, 'load failed');
  assert.equal(page.context.fetch, page.fetch);
  page.context.composeUiBuilderBoot.done();
  assert.equal(page.context.fetch, page.fetch);
  assert.equal(page.intervals.size, 0);
});

test('startup marks survive dismissal, stay bounded and never claim a preview is ready', () => {
  const page = bootPage();
  const api = page.context.composeUiBuilderBoot;
  api.phase('Checking who you are');
  api.phase('Opening the design');
  api.done();
  const marks = page.context.__uiBuilderStartup.marks;
  assert.equal(marks['editor-ready'], undefined);
  assert.equal(marks['preview-rendered'], undefined);
  const hiddenAt = marks['boot-hidden'];
  api.done();
  api.mark('renderer-ready');
  api.mark('preview-rendered');
  const previewAt = marks['preview-rendered'];
  for (let i = 0; i < 1000; i++) {
    api.mark('preview-rendered');
    api.mark('document-' + i);
    api.phase('Opening the design');
  }
  assert.equal(marks['boot-hidden'], hiddenAt);
  assert.equal(marks['preview-rendered'], previewAt);
  assert.ok(marks['renderer-ready'] > hiddenAt);
  assert.deepEqual(Object.keys(marks), [
    'script', 'identity-start', 'design-start', 'boot-hidden', 'renderer-ready', 'preview-rendered',
  ]);
  assert.equal(page.performanceMarks.length, Object.keys(marks).length);
  assert.equal(page.intervals.size, 0);
});

test('a host without a boot screen keeps its own fetch', () => {
  const page = bootPage({ withBoot: false });
  assert.equal(page.context.fetch, page.fetch);
  assert.equal(page.context.composeUiBuilderBoot, undefined);
  assert.equal(page.intervals.size, 0);
});
