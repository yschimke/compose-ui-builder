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
