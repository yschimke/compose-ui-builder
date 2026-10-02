// The editor's service worker: enough of the app kept on this device for a cold start offline.
//
// Registered only when a person has opted in — the editor installed as an app, `?storage=local`,
// or `?offline=1` (see `ui-builder-boot.js`) — because a worker at `/ui-builder/` controls every
// builder page on the origin, and a page nobody asked to be offline should never be answered from a
// cache. `?sw=off` on any builder page unregisters it.
//
// What it does, and the one rule behind each:
// - **Navigations: network first.** The shell is the one document a rollout has to be able to
//   change, so the server answers whenever it can. Only when it cannot is the last shell this
//   device saw served — and only for an editor route, never for the designs index or a sharing
//   page, which are server pages that a stale copy would misrepresent.
// - **The bundle: cache first.** Everything the shell names is precached on install, and under a
//   server's content-addressed prefix (`/ui-builder/v/<digest>/…`) a URL never changes meaning.
//   The precached copies of unversioned URLs (a bundle served as plain files) are just as fixed:
//   the cache is named for this bundle's digest, so a new bundle is a new worker and a new cache.
// - **Never** the API, the design data, the catalog runtimes or anything that is not a GET. The
//   API is outside this worker's scope (`/api/…`) to begin with; the runtimes run in sandboxed
//   frames and have their own immutable caching; everything else passes straight through.
//
// The file name and its place at the bundle root are a contract with the host: it must be served at
// `<editor root>/ui-builder-sw.js` with `Cache-Control: no-cache`, so a new release is noticed on the
// next navigation rather than a day later.

// Written in by the build (`wasmFrontendDist`): a digest of every other file in this bundle.
const VERSION = '@UI_BUILDER_SW_VERSION@';
// Every file of the bundle the editor may fetch, bundle-relative, also written in by the build: the
// first visit fetches them before this worker controls the page, so they are precached rather than
// learned, and the next cold start offline finds them all.
const BUNDLE_FILES = JSON.parse('@UI_BUILDER_SW_BUNDLE@'.replace(/^@.*@$/, '[]'));

const CACHE_PREFIX = 'ui-builder-shell-';
const CACHE = CACHE_PREFIX + VERSION;
// Unversioned files fetched at run time: kept for offline, but always asked of the network first.
const RUNTIME_CACHE = CACHE + '-runtime';
const SCOPE = new URL(self.registration.scope);
// The shell is cached under the scope root whatever design it was fetched for: it is the same app
// for every design, which reads its design id back out of the address bar.
const SHELL_KEY = new URL('./', SCOPE).href;

// Server pages under the editor's root that are not the editor. Never cached, never faked.
const SERVER_PAGES = /^(designs|request-access)(\/|$)|^[^/]+\/(access|history|delete|folder)(\/|$)/;

self.addEventListener('install', (event) => {
  event.waitUntil(precache());
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(
        names
          .filter((name) => name.startsWith(CACHE_PREFIX) && name !== CACHE && name !== RUNTIME_CACHE)
          .map((name) => caches.delete(name)),
      );
      await self.clients.claim();
    })(),
  );
});

// The page asks for this when a person presses Reload on the update notice.
self.addEventListener('message', (event) => {
  if (event.data && event.data.type === 'SKIP_WAITING') self.skipWaiting();
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== SCOPE.origin || !url.pathname.startsWith(SCOPE.pathname)) return;
  const rest = url.pathname.slice(SCOPE.pathname.length);
  if (rest.startsWith('runtime/') || rest.includes('/api/') || SERVER_PAGES.test(rest)) return;
  if (request.mode === 'navigate') {
    if (isEditorRoute(rest)) event.respondWith(navigate(request));
    return;
  }
  if (rest.startsWith('v/')) {
    event.respondWith(cacheFirst(request));
    return;
  }
  if (rest.startsWith('composeResources/') || /\.(wasm|mjs|js|json|ttf|otf|woff2?)$/.test(rest)) {
    event.respondWith(precachedOrNetwork(request));
  }
});

/** The scope root, `index.html`, a design id, or a catalog's home: the routes the shell answers. */
function isEditorRoute(rest) {
  if (rest === '' || rest === 'index.html') return true;
  if (!/^[^/]+\/?$/.test(rest)) return false;
  // A design id may contain a dot; a file in the bundle is not a design.
  return !/\.(html?|m?js|wasm|json|ttf|otf|woff2?|txt|md|png|svg|ico|css)$/.test(rest);
}

async function precache() {
  const cache = await caches.open(CACHE);
  const shellResponse = await fetch(SHELL_KEY, { cache: 'no-cache', credentials: 'same-origin' });
  if (!shellResponse.ok) throw new Error('the editor shell answered HTTP ' + shellResponse.status);
  const html = await shellResponse.clone().text();
  const assets = shellAssets(html, shellResponse.url || SHELL_KEY);
  // The bundle sits beside the module (under a server's versioned prefix), which is where the
  // module, the fonts and the Compose resources are resolved from. The page reads its JSON fixtures
  // relative to itself instead, so those are kept at the editor root too.
  const module = assets.find((asset) => /\/uiBuilder\.mjs$/.test(new URL(asset).pathname));
  const assetBase = module ? new URL('./', module) : new URL('./', SHELL_KEY);
  const bundle = BUNDLE_FILES.flatMap((file) => [
    new URL(file, assetBase).href,
    ...(/^[^/]+\.json$/.test(file) ? [new URL(file, SHELL_KEY).href] : []),
  ]);
  const urls = [...new Set([...assets, ...bundle])];
  // One request at a time: two 30 MB Wasm modules in parallel is the kind of burst that a phone on
  // a weak connection, or a proxy, drops — and a failed precache is a worker that never installs.
  for (const url of urls) {
    const response = await fetch(url, { credentials: 'same-origin' });
    if (!response.ok) throw new Error('could not precache ' + url + ': HTTP ' + response.status);
    await cache.put(url, response);
  }
  await cache.put(SHELL_KEY, shellResponse);
}

/** Every same-origin file the shell names: `src`/`href` attributes and import-map targets. */
function shellAssets(html, base) {
  const found = new Set();
  const add = (reference) => {
    try {
      const url = new URL(reference, base);
      if (url.origin === SCOPE.origin && url.pathname.startsWith(SCOPE.pathname)) {
        url.hash = '';
        found.add(url.href);
      }
    } catch (e) {}
  };
  for (const match of html.matchAll(/\b(?:src|href)=["']?([^"'\s>]+)/g)) add(match[1]);
  for (const match of html.matchAll(/"(\.\/[^"]+)"/g)) add(match[1]);
  return [...found].filter((url) => /\.(wasm|mjs|js|json|css|ttf|woff2?|png|svg|ico)$/.test(new URL(url).pathname));
}

async function navigate(request) {
  const cache = await caches.open(CACHE);
  try {
    const response = await fetch(request);
    const type = response.headers.get('content-type') || '';
    if (response.ok && !response.redirected && type.includes('text/html')) {
      // The newest shell this device has seen, so an offline start opens what was last served.
      await cache.put(SHELL_KEY, response.clone());
    }
    return response;
  } catch (offline) {
    const cached = await cache.match(SHELL_KEY);
    if (cached) return cached;
    throw offline;
  }
}

async function cacheFirst(request) {
  const cache = await caches.open(CACHE);
  const cached = await cache.match(request, { ignoreVary: true });
  if (cached) return cached;
  const response = await fetch(request);
  if (response.ok && response.type === 'basic') await cache.put(request, response.clone());
  return response;
}

/**
 * The precached copy of an unversioned bundle file, else the network — and, offline, whatever copy
 * an earlier online visit left. Files that were not precached are asked of the network every time,
 * because nothing in their URL says they cannot change.
 */
async function precachedOrNetwork(request) {
  const precached = await (await caches.open(CACHE)).match(request, { ignoreVary: true });
  if (precached) return precached;
  const runtime = await caches.open(RUNTIME_CACHE);
  try {
    const response = await fetch(request);
    if (response.ok && response.type === 'basic') await runtime.put(request, response.clone());
    return response;
  } catch (offline) {
    const stale = await runtime.match(request, { ignoreVary: true });
    if (stale) return stale;
    throw offline;
  }
}
