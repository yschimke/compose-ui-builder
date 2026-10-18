import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, runInContext } from 'node:vm';
import { test } from 'node:test';

const source = await readFile(new URL('../../ui-builder/src/wasmJsMain/resources/ui-builder-boot.js', import.meta.url), 'utf8');

function bootPage({ withBoot = true, browser = {} } = {}) {
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
    ...browser,
  });
  context.window = context;
  context.top = context;
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

function offlineBoot({rooted = true, shared = '/push-sw.js', search = '?storage=local'} = {}) {
  const registrations = [];
  const removed = [];
  const deleted = [];
  const context = {
    isSecureContext: true,
    location: {origin: 'https://example.test', pathname: rooted ? '/' : '/ui-builder/', search},
    URL, URLSearchParams,
    document: {readyState: 'complete', documentElement: {dataset: {}}, getElementById: () => null,
      querySelector: selector => ({getAttribute: () => selector.includes('base-path') ? (rooted ? '/' : '') : shared})},
    caches: {keys: async () => ['ui-builder-shell-old', 'notification-data'], delete: async name => deleted.push(name)},
    navigator: {serviceWorker: {getRegistrations: async () => [{scope: 'https://example.test' + (rooted ? '/' : '/ui-builder/'), unregister: async () => removed.push(true)}], addEventListener() {}, register: async (url, options) => {
      registrations.push({url, scope: options.scope});
      return {addEventListener() {}};
    }}},
  };
  // runInContext receives the same context object as its globalThis.
  context.top = context;
  const page = bootPage({withBoot: false, browser: context});
  return {registrations, page, removed, deleted};
}

test('root offline uses the host-advertised notification worker and the same root registration', () => {
  const {registrations} = offlineBoot();
  assert.deepEqual(registrations, [{url: 'https://example.test/push-sw.js', scope: 'https://example.test/'}]);
});

test('older root hosts and non-opted-in visits keep notification registration untouched', () => {
  assert.deepEqual(offlineBoot({shared: null}).registrations, []);
  assert.deepEqual(offlineBoot({shared: '/foreign-worker.js'}).registrations, []);
  assert.deepEqual(offlineBoot({search: ''}).registrations, []);
});

test('prefixed editors retain their narrower offline registration', () => {
  assert.deepEqual(offlineBoot({rooted: false}).registrations,
    [{url: 'https://example.test/ui-builder/ui-builder-sw.js', scope: 'https://example.test/ui-builder/'}]);
});

test('clearing offline caches preserves the shared notification registration', async () => {
  const root = offlineBoot({search: '?sw=off'});
  const prefixed = offlineBoot({rooted: false, search: '?sw=off'});
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(root.removed, []);
  assert.deepEqual(prefixed.removed, [true]);
  assert.deepEqual(root.deleted, ['ui-builder-shell-old']);
  assert.deepEqual(prefixed.deleted, ['ui-builder-shell-old']);
});
