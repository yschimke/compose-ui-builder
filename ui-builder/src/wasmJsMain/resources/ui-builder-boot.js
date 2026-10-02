// The boot screen's moving parts: real download progress for the two Wasm modules, the phase line,
// and something to read while a first visit fetches several megabytes.
//
// A classic script, loaded before the module, because it has to be in place before `uiBuilder.mjs`
// and `skiko.mjs` call `fetch` for their `.wasm`. The page draws the boot screen from HTML and CSS
// alone and the editor removes it itself (`dismissBootScreen` in Main.kt), so a host whose CSP
// blocks this file loses the progress bar and nothing else.
(() => {
  const boot = document.getElementById('ui-builder-boot');
  if (!boot) return;
  const bar = boot.querySelector('[data-boot-bar]');
  const phase = boot.querySelector('[data-boot-phase]');
  const detail = boot.querySelector('[data-boot-detail]');
  const quip = boot.querySelector('[data-boot-quip]');

  // Decoded bytes of `uiBuilder.wasm` + `skiko.wasm`, written in by `wasmFrontendDist`. Counted
  // after decoding, so this is right whether or not a proxy compressed the response, which is when
  // `Content-Length` stops meaning the number of bytes the stream will deliver. Unsubstituted (a
  // hand-assembled dist) it is not a number and the bar falls back to the headers.
  const expectedTotal = Number('@UI_BUILDER_WASM_BYTES@');

  const downloads = [];
  let finished = false;

  const megabytes = (bytes) => (bytes / (1024 * 1024)).toFixed(1);

  function render() {
    if (finished || downloads.length === 0) return;
    const loaded = downloads.reduce((sum, d) => sum + d.loaded, 0);
    const headerTotal = downloads.every((d) => d.total > 0)
      ? downloads.reduce((sum, d) => sum + d.total, 0)
      : 0;
    const total = expectedTotal > 0 ? expectedTotal : headerTotal;
    const allDone = downloads.every((d) => d.done);
    if (allDone) {
      // Streaming compilation has been running alongside the download; what is left is the tail
      // of it and the first frame, which the editor reports through `phase` from here.
      setProgress(0.92);
      setPhase('Compiling the editor', '');
      return;
    }
    if (total > 0) {
      setProgress(Math.min(loaded / total, 1) * 0.9);
      setPhase('Downloading the editor', megabytes(loaded) + ' of ' + megabytes(total) + ' MB');
    } else {
      setPhase('Downloading the editor', megabytes(loaded) + ' MB');
    }
  }

  function setProgress(fraction) {
    if (!bar) return;
    boot.dataset.progress = 'determinate';
    bar.style.transform = 'scaleX(' + Math.max(0.02, fraction) + ')';
  }

  function setPhase(text, extra) {
    if (phase && text) phase.textContent = text;
    if (detail) detail.textContent = extra || '';
  }

  // Count the bytes of each `.wasm` response as the engine reads them. The body is re-wrapped in a
  // stream that passes every chunk straight through, and the headers are kept, so
  // `instantiateStreaming` still sees `application/wasm` and still compiles while it downloads.
  const nativeFetch = globalThis.fetch;
  if (typeof nativeFetch === 'function' && typeof ReadableStream === 'function') {
    globalThis.fetch = function (input, init) {
      const pending = nativeFetch.apply(this, arguments);
      let url = '';
      try {
        url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
      } catch (_) {}
      if (finished || !/\.wasm(?:[?#]|$)/.test(url)) return pending;
      return pending.then((response) => {
        if (!response.ok || !response.body) return response;
        const encoded = response.headers.get('content-encoding');
        const length = Number(response.headers.get('content-length'));
        const entry = { loaded: 0, total: !encoded && length > 0 ? length : 0, done: false };
        downloads.push(entry);
        render();
        const reader = response.body.getReader();
        const counted = new ReadableStream({
          async pull(controller) {
            const { done, value } = await reader.read();
            if (done) {
              entry.done = true;
              render();
              controller.close();
              return;
            }
            entry.loaded += value.byteLength;
            render();
            controller.enqueue(value);
          },
          cancel(reason) {
            return reader.cancel(reason);
          },
        });
        return new Response(counted, {
          status: response.status,
          statusText: response.statusText,
          headers: response.headers,
        });
      });
    };
  }

  // Deliberately silly. A first visit is a few seconds of download no matter what; a line worth
  // reading makes that feel shorter, and it changes often enough to show the page is alive.
  const quips = [
    'Shouting at designers to finish the mocks…',
    'Moving the button one pixel left. No, back…',
    'Negotiating padding: 12dp, final offer…',
    'Asking Material 3 how rounded is too rounded…',
    'Hoisting state somewhere sensible…',
    'Convincing the recomposer this is the last time…',
    'Waking up the WebGL hamsters…',
    'Remembering { } things so you do not have to…',
    'Reticulating Modifier chains…',
    'Politely asking Skia to draw the rest of the owl…',
    'Checking the design works in dark mode. It does now…',
    'Kerning. Un-kerning. Re-kerning…',
    'Aligning things to an 8dp grid that nobody else can see…',
    'Explaining to the PM why the logo cannot be bigger…',
  ];
  const reduceMotion = globalThis.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  let quipIndex = Math.floor(Math.random() * quips.length);
  const showQuip = () => {
    if (!quip) return;
    quip.textContent = quips[quipIndex % quips.length];
    quipIndex += 1;
  };
  showQuip();
  const quipTimer = setInterval(showQuip, reduceMotion ? 6000 : 2800);

  // An editor that fails before its first frame would otherwise leave the boot screen spinning
  // forever over a blank page. Say so where the person is looking; the console has the detail.
  const fail = (reason) => {
    if (finished) return;
    boot.dataset.state = 'failed';
    setPhase('The editor could not start', String(reason ?? '').slice(0, 300));
    if (quip) quip.textContent = 'Nobody shouted loud enough. The browser console says why.';
    clearInterval(quipTimer);
  };
  globalThis.addEventListener('error', (event) => {
    if (event.target === globalThis || event.target === window) fail(event.message);
  });
  globalThis.addEventListener('unhandledrejection', (event) => fail(event.reason?.message ?? event.reason));

  // The editor's half: `phase` while it loads the design, `done` to take the screen away.
  globalThis.composeUiBuilderBoot = {
    phase(text, progress) {
      if (finished) return;
      setPhase(text, '');
      if (typeof progress === 'number') setProgress(progress);
    },
    done() {
      if (finished) return;
      finished = true;
      clearInterval(quipTimer);
      globalThis.fetch = nativeFetch;
    },
  };
})();

// The offline service worker (`ui-builder-sw.js`), registered only for a person who asked for it.
//
// A worker at the editor's root controls every builder page on this origin — including every
// automated harness that drives one — so it is never registered on its own: only when the editor is
// running as an installed app, or the address says `?storage=local` (a design kept in this browser,
// whose point is to work offline) or `?offline=1`. `?sw=off` takes it away again, caches included.
//
// The script and the scope are resolved against the page, not hard-coded, so the same bundle works
// behind compose-preview-server (`/ui-builder/…`, assets under a versioned prefix) and served as
// plain files from anywhere. Registered after `load`, so it never competes with the Wasm download.
(() => {
  try {
    if (!('serviceWorker' in navigator) || !globalThis.isSecureContext) return;
    // An embedded editor (an IDE webview, an MCP App frame) belongs to its host, not to this origin.
    if (globalThis.top !== globalThis || globalThis.composeUiBuilderMcpApp) return;
  } catch (_) {
    return;
  }
  const params = new URLSearchParams(location.search);
  const marker = '/ui-builder/';
  const at = location.pathname.indexOf(marker);
  const root =
    at >= 0
      ? location.pathname.slice(0, at + marker.length)
      : location.pathname.slice(0, location.pathname.lastIndexOf('/') + 1);
  const scope = new URL(root, location.origin).href;
  const script = new URL('ui-builder-sw.js', scope).href;

  if (params.get('sw') === 'off') {
    navigator.serviceWorker
      .getRegistrations()
      .then((registrations) =>
        Promise.all(registrations.filter((r) => r.scope === scope).map((r) => r.unregister())),
      )
      .then(() => globalThis.caches?.keys())
      .then((names) =>
        Promise.all(
          (names || []).filter((n) => n.startsWith('ui-builder-shell-')).map((n) => caches.delete(n)),
        ),
      )
      .catch((error) => console.warn('ui-builder: could not remove the offline worker', error));
    return;
  }

  const installed = ['standalone', 'fullscreen', 'minimal-ui', 'window-controls-overlay'].some(
    (mode) => globalThis.matchMedia?.('(display-mode: ' + mode + ')').matches,
  ) || navigator.standalone === true;
  const optedIn = installed || params.get('storage') === 'local' || params.get('offline') === '1';
  if (!optedIn) return;

  // A small notice, outside the editor's canvas, when a newer editor is installed and waiting.
  const offerUpdate = (registration) => {
    if (!registration.waiting || !navigator.serviceWorker.controller) return;
    if (document.getElementById('ui-builder-update')) return;
    const notice = document.createElement('div');
    notice.id = 'ui-builder-update';
    notice.setAttribute('role', 'status');
    notice.style.cssText =
      'position:fixed;z-index:20;left:50%;transform:translateX(-50%);' +
      'bottom:calc(72px + env(safe-area-inset-bottom, 0px));display:flex;gap:12px;align-items:center;' +
      'padding:8px 8px 8px 16px;border-radius:999px;background:#322f35;color:#f5eff7;' +
      'font:14px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;box-shadow:0 2px 8px #0005';
    const text = document.createElement('span');
    text.textContent = 'A new version of the editor is ready';
    const reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = 'Reload';
    reload.style.cssText =
      'border:0;border-radius:999px;padding:6px 14px;background:#d0bcff;color:#381e72;font:inherit;' +
      'font-weight:600;cursor:pointer';
    reload.addEventListener('click', () => {
      reload.disabled = true;
      registration.waiting?.postMessage({ type: 'SKIP_WAITING' });
    });
    const dismiss = document.createElement('button');
    dismiss.type = 'button';
    dismiss.textContent = '×';
    dismiss.setAttribute('aria-label', 'Dismiss');
    dismiss.style.cssText =
      'border:0;background:transparent;color:inherit;font:inherit;font-size:18px;cursor:pointer;padding:0 8px';
    dismiss.addEventListener('click', () => notice.remove());
    notice.append(text, reload, dismiss);
    document.body.appendChild(notice);
  };

  let reloading = false;
  navigator.serviceWorker.addEventListener('controllerchange', () => {
    // Only after the person pressed Reload: a first install also changes the controller.
    if (reloading || !document.getElementById('ui-builder-update')) return;
    reloading = true;
    location.reload();
  });

  const register = () =>
    navigator.serviceWorker
      .register(script, { scope, updateViaCache: 'none' })
      .then((registration) => {
        document.documentElement.dataset.uiBuilderOffline = 'registered';
        offerUpdate(registration);
        registration.addEventListener('updatefound', () => {
          const incoming = registration.installing;
          incoming?.addEventListener('statechange', () => {
            if (incoming.state === 'installed') offerUpdate(registration);
          });
        });
      })
      .catch((error) => {
        document.documentElement.dataset.uiBuilderOffline = 'failed';
        console.warn('ui-builder: the offline worker could not be registered', error);
      });
  if (document.readyState === 'complete') register();
  else globalThis.addEventListener('load', register, { once: true });
})();
