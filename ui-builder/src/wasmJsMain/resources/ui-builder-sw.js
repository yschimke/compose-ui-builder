// The editor's service worker: enough of the app kept on this device for a cold start offline.
//
// Registered only on opt-in (the editor installed as an app, `?storage=local`, or `?offline=1`; see
// `ui-builder-boot.js`), since a worker at `/ui-builder/` controls every builder page on the
// origin. Root hosts compose it with the notification worker at the host-advertised URL.
// `?sw=off` clears editor caches and unregisters only standalone editor registrations.
//
// - **Navigations: network first.** Only when the server can't answer is the last shell served, and
//   only for editor routes (never the designs index or sharing pages).
// - **The bundle: cache first.** Everything the shell names is precached on install; the cache is
//   named for this bundle's digest, so a new bundle means a new worker and cache.
// - **Never** the API, design data, catalog runtimes or non-GET requests.
//
// Contract with the host: served at `<editor root>/ui-builder-sw.js` with `Cache-Control:
// no-cache`, so a release is noticed on the next navigation.

// Written in by the build (`wasmFrontendDist`): a digest of every other file in this bundle.
const VERSION = '@UI_BUILDER_SW_VERSION@';
// Every bundle file the editor may fetch, bundle-relative, written in by the build; precached
// because the first visit fetches them before this worker controls the page.
const BUNDLE_FILES = JSON.parse('@UI_BUILDER_SW_BUNDLE@'.replace(/^@.*@$/, '[]'));

const CACHE_PREFIX = 'ui-builder-shell-';
const CACHE = CACHE_PREFIX + VERSION;
// Unversioned files fetched at run time: kept for offline, but always asked of the network first.
const RUNTIME_CACHE = CACHE + '-runtime';
const SCOPE = new URL(self.registration.scope);
// The shell is cached under the scope root whatever design it was fetched for: it is the same app
// for every design, which reads its design id back out of the address bar.
const SHELL_KEY = new URL('./', SCOPE).href;
const PRECACHE_KEY = new URL('__ui-builder-precache-complete', SCOPE).href;
// Supplied by a root host's shared notification worker, never inferred from a broad scope.
const ROOT_CONFIG = self.composeUiBuilderOffline;
const ASSET_ROOT = ROOT_CONFIG ? new URL(ROOT_CONFIG.assetBase, SCOPE) : SCOPE;
const RESERVED = new Set(ROOT_CONFIG?.reservedPaths || []);

// Server pages under the editor's root that are not the editor. Never cached, never faked.
const SERVER_PAGES = /^(designs|projects|request-access)(\/|$)|^[^/]+\/(access|history|delete|folder)(\/|$)/;

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
  if (event.data?.type === 'ENSURE_PRECACHE') {
    event.waitUntil((async () => {
      try {
        const cache = await caches.open(CACHE);
        if (!await cache.match(PRECACHE_KEY)) await precache();
        event.ports?.[0]?.postMessage({ ok: true });
      } catch (_) {
        // Offline visits may use an existing shell even when a refresh cannot finish.
        event.ports?.[0]?.postMessage({ ok: false });
      }
    })());
  }
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET') return;
  const url = new URL(request.url);
  if (url.origin !== SCOPE.origin || !url.pathname.startsWith(SCOPE.pathname)) return;
  const rest = url.pathname.slice(SCOPE.pathname.length);
  if (request.mode === 'navigate') {
    // Root hosts reserve API/auth/admin and every other server route using the server's own list.
    let first;
    try { first = decodeURIComponent(rest.split('/')[0]); } catch (_) { return; }
    if (!RESERVED.has(first) && !SERVER_PAGES.test(rest) && isEditorRoute(rest)) {
      event.respondWith(navigate(request));
    }
    return;
  }
  if (ROOT_CONFIG && /^[^/]+\.json$/.test(rest) && !RESERVED.has(rest)) {
    // Only bundle fixtures already precached by name; arbitrary server JSON is never stored.
    event.respondWith((async () => {
      const cache = await caches.open(CACHE);
      return (await cache.match(request, { ignoreVary: true })) || fetch(request);
    })());
    return;
  }
  // Only bundle paths may use the asset caches. A broad root scope must never turn an API,
  // catalog runtime, credential response or arbitrary JSON endpoint into offline data.
  if (url.pathname.startsWith(ASSET_ROOT.pathname)) {
    const asset = url.pathname.slice(ASSET_ROOT.pathname.length);
    if (asset.startsWith('runtime/') || asset.includes('/api/') || SERVER_PAGES.test(asset)) return;
    if (asset.startsWith('v/')) {
      event.respondWith(cacheFirst(request));
      return;
    }
    if (asset.startsWith('composeResources/') || /\.(wasm|mjs|js|json|ttf|otf|woff2?)$/.test(asset)) {
      event.respondWith(precachedOrNetwork(request));
    }
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
  // The module, fonts and Compose resources resolve beside the module (under a server's versioned
  // prefix); the page reads its JSON fixtures relative to itself, so those are kept at the editor
  // root too.
  const module = assets.find((asset) => /\/uiBuilder\.mjs$/.test(new URL(asset).pathname));
  const assetBase = module ? new URL('./', module) : new URL('./', SHELL_KEY);
  const bundle = BUNDLE_FILES.flatMap((file) => [
    new URL(file, assetBase).href,
    ...(/^[^/]+\.json$/.test(file) ? [new URL(file, SHELL_KEY).href] : []),
  ]);
  const bundleAssets = assets.filter(url =>
    !ROOT_CONFIG || new URL(url).pathname.startsWith(ASSET_ROOT.pathname));
  const urls = [...new Set([...bundleAssets, ...bundle])];
  // One request at a time: two 30 MB Wasm modules in parallel is the kind of burst that a phone on
  // a weak connection, or a proxy, drops — and a failed precache is a worker that never installs.
  for (const url of urls) {
    const response = await fetch(url, { credentials: 'same-origin' });
    if (!response.ok) throw new Error('could not precache ' + url + ': HTTP ' + response.status);
    await cache.put(url, response);
  }
  await cache.put(SHELL_KEY, shellResponse);
  await cache.put(PRECACHE_KEY, new Response('complete'));
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
 * The precached copy of an unversioned bundle file, else the network, and offline whatever an
 * earlier visit cached. Non-precached files always go to the network.
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
