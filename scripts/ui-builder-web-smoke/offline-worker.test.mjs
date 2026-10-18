import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createContext, runInContext } from 'node:vm';
import { test } from 'node:test';
const source = await readFile(
  new URL('../../ui-builder/src/wasmJsMain/resources/ui-builder-sw.js', import.meta.url),
  'utf8'
);
function worker(rooted = true) {
  const listeners = new Map();
  const self = {
    registration: { scope: 'https://example.test' + (rooted ? '/' : '/ui-builder/') },
    addEventListener: (type, callback) => listeners.set(type, callback)
  };
  if (rooted)
    self.composeUiBuilderOffline = {
      assetBase: '/ui-builder/',
      reservedPaths: [
        'api',
        'admin',
        'auth',
        'oauth',
        'healthz',
        'version',
        'status.json',
        'assets',
        'ui-builder',
        'push-sw.js'
      ]
    };
  runInContext(
    source,
    createContext({
      self,
      URL,
      Set,
      fetch: async () => new Response('network'),
      Response,
      caches: { open: async () => ({ match: async () => null }) }
    })
  );
  return (path, { mode = 'navigate', method = 'GET', origin = 'https://example.test' } = {}) => {
    let response;
    listeners.get('fetch')({
      request: { url: origin + path, mode, method },
      respondWith: (p) => {
        response = p;
      }
    });
    return response;
  };
}
test('root navigation caches editor pages but leaves server and management pages alone', async () => {
  const fetch = worker();
  for (const path of ['/', '/my-design', '/some.design']) assert.ok(fetch(path), path);
  for (const path of [
    '/api',
    '/admin',
    '/auth',
    '/oauth',
    '/healthz',
    '/version',
    '/status.json',
    '/designs',
    '/projects',
    '/request-access',
    '/my-design/access',
    '/%61dmin',
    '/%ZZ'
  ])
    assert.equal(fetch(path), undefined, path);
});
test('root scope never caches API data, runtimes, mutations or another origin', () => {
  const fetch = worker();
  for (const path of [
    '/api/data.json',
    '/admin/config.json',
    '/ui-builder/runtime/pin/app.js',
    '/assets/private.json'
  ])
    assert.equal(fetch(path, { mode: 'cors' }), undefined, path);
  assert.equal(fetch('/design', { method: 'POST' }), undefined);
  assert.equal(fetch('/design', { origin: 'https://other.test' }), undefined);
  assert.ok(fetch('/ui-builder/v/digest/uiBuilder.wasm', { mode: 'cors' }));
});
test('prefixed worker continues to serve only editor routes and bundle resources', () => {
  const fetch = worker(false);
  assert.ok(fetch('/ui-builder/my-design'));
  assert.ok(fetch('/ui-builder/v/pin/app.wasm', { mode: 'cors' }));
  assert.equal(fetch('/api'), undefined);
  assert.equal(fetch('/ui-builder/runtime/pin/app.js', { mode: 'cors' }), undefined);
  assert.equal(fetch('/ui-builder/projects'), undefined);
});

test('retained root worker restores every bundle resource after caches are cleared', async () => {
  const listeners = new Map();
  const entries = new Map();
  const requests = [];
  const cache = {
    match: async key => entries.get(key),
    put: async (key, value) => entries.set(key, value)
  };
  runInContext(source.replace('@UI_BUILDER_SW_BUNDLE@', '["unused.json"]'), createContext({
    self: {
      registration: {scope: 'https://example.test/'},
      composeUiBuilderOffline: {assetBase: '/ui-builder/', reservedPaths: ['api']},
      addEventListener: (type, listener) => listeners.set(type, listener)
    }, URL, Set, Response,
    fetch: async url => { requests.push(url); return new Response('<html></html>'); },
    caches: {open: async () => cache}
  }));
  async function ensure() {
    let done;
    let ok;
    listeners.get('message')({
      data: {type: 'ENSURE_PRECACHE'},
      ports: [{postMessage: value => {ok = value.ok;}}],
      waitUntil: promise => {done = promise;}
    });
    await done;
    assert.equal(ok, true);
  }
  await ensure();
  assert.ok(entries.has('https://example.test/unused.json'));
  const count = requests.length;
  await ensure();
  assert.equal(requests.length, count, 'complete caches do not redownload the bundle');
  entries.clear();
  await ensure();
  assert.ok(entries.has('https://example.test/unused.json'));
  assert.equal(requests.length, count * 2, 'cleared caches restore even unused resources');
});
