# Testing the editor as an agent

Three ways to see the editor work, in the order to try them. Each was verified end to end in a
cloud agent sandbox (Linux, no GPU, outbound traffic through an HTTPS proxy). Read
[Sandbox gotchas](#sandbox-gotchas) before debugging a failure: most of them are the sandbox, not
the editor.

| Option | Needs | Shows | Use it to |
| --- | --- | --- | --- |
| 1. Desktop JVM app under Xvfb | JDK 21, Xvfb, Gradle deps | The real editor, all three catalogs | Look at and click through Material 3, Wear M3 and Wear widget designs |
| 2. Web smoke test | Built `wasmDist`, Chromium | That the Wasm page boots (`data-ui-builder-ready`) | Prove a change did not break the browser build |
| 3. Deployed editor `https://preview.coo.ee/ui-builder/` | A browser that can fetch ~38 MB of Wasm | The shipped web build | Compare against the desktop app. Often **not** usable from a sandbox, see below |

The desktop app is the one to reach for when the question is "how does it look and feel". It is a
separate Compose Desktop target (`:ui-builder-desktop`), so the Wasm build and WebGL are not
involved at all.

## 1. Desktop JVM app under Xvfb

```sh
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64     # see gotcha 2 — do not skip
./gradlew --stop                                        # a daemon on another JDK will be reused
xvfb-run -a -s "-screen 0 1280x900x24" \
  build-brief ./gradlew --max-workers=1 :ui-builder-desktop:run --args='--catalog remote-m3' &
```

- `--catalog remote-m3` is the Wear widget catalog; omit it for Material 3. `--template <id>`
  starts from a template (`weather-widget`, …). Both are listed in
  [`UI_BUILDER_GETTING_STARTED.md`](UI_BUILDER_GETTING_STARTED.md).
- The first build downloads a lot. Expect several minutes and, in a sandbox, HTTP 429s (gotcha 1).
- The window opens at 800×600 in the **compact layout**: the canvas on top, and a bottom tab bar
  (Components, Layers, Properties, Code) whose panels are a bottom sheet. That is the closest thing
  here to a phone, but it is a landscape window, not a phone-shaped one.
- **File** (top left of the window) is the only way to pick a form factor: *New Material 3 design*,
  *New Wear M3 design*, *New Wear widgets design*, or *New from template*.

### Seeing and driving it

Xvfb has no screenshot tools installed, so use Java's `Robot`. It is committed at
[`scripts/ui-builder-desktop-drive/`](../scripts/ui-builder-desktop-drive/drive.sh):

```sh
D=scripts/ui-builder-desktop-drive/drive.sh
$D shot now.png                                           # screenshot the whole display
$D click 66 57 click 122 136 wait 400 shot templates.png  # File > New from template
```

Commands are `click X Y`, `wait MS`, `key KEYCODE`, `shot FILE`, chained in one call. Coordinates
are screen pixels of the 1280×900 display. Read the PNG afterwards to look at it.

Traps:

- A click that lands on an open bottom sheet is swallowed by the sheet. Close the sheet (tap its
  tab again) before clicking the canvas.
- A Swing menu stays open across separate `drive.sh` calls. Chain the whole menu path in one call
  or start from a `shot` to see what is open.
- Stop it with `pkill -f desktop.MainKt; pkill Xvfb`.

## 2. Web smoke test

`scripts/ui-builder-web-smoke/smoke.mjs` serves the built `wasmFrontendDist` and opens
`?mode=reference` and `?mode=interactive-editor` in Chromium, failing unless the page sets
`data-ui-builder-ready` with no page error. It proves the Wasm build boots; it does not prove the
editor looks right. It needs the Wasm build, which is the heaviest thing in the repo to compile, so
prefer CI for it.

It runs five lanes, all by default; `SMOKE_LANES=mobile,offline node smoke.mjs <wasmDist>` picks
some:

- `desktop` — the capture modes at 1400×900.
- `mobile` — the interactive editor as a phone opens it: 412×915 CSS px, device pixel ratio 2.625,
  `isMobile`, `hasTouch`. This is the compact layout under real mobile emulation, which the desktop
  app under Xvfb (option 1) cannot give.
- `offline` — the phone again with `?offline=1`: waits for the service worker to control the page,
  cuts the network, and opens the editor in a fresh tab, which must still come up ready. `127.0.0.1`
  is a secure context, so the worker registers over plain HTTP there.

- `live` — opens a saved Jetcaster snapshot through the HTTP protocol, checks the first paint
  precedes the optional catalog-list request, and verifies a catalog-list outage leaves the design open.
- `cache` — starts Chromium three times with the same temporary disk profile and checks the second
  and third visits consume compiled-code cache entries for both Wasm modules. The profile is removed
  afterwards; `wasm-cache.json` and per-visit Wasm traces are kept beside the screenshots.

In a sandbox, Playwright's Chromium may already be installed (for example under `/opt/pw-browsers`
with a global `playwright` package); link that package into a `node_modules` above the script
rather than running `npx playwright install`.

### Cached startup measurements

The capture fixtures use the same coalesced inspection publisher as saved designs. Repeated
whole-document encoding in each node-layout callback otherwise inflates the fixture timing.
Font registration still precedes composition because Wear caches its first resolved typeface.

The [font-transfer experiment](evidence/design-first-startup/font-experiment.json) records why
a faster individual stage is not enough: direct binary transfer shortened the font gate but
regressed full warm startup, so it was not shipped. The accompanying `before.png` is the prior
profile's coalesced-fixture render; `after.png` is the retained-font-path render. They are pixel-identical.


The desktop and mobile smoke lanes also reopen the editor in a fresh tab in the same browser
context, using an isolated temporary disk profile. Incognito memory caches may reject the large
Wasm assets and cannot reliably exercise this path. Static assets are immutable for the lifetime of
the test server. The warm visit must load both Wasm resources from the HTTP cache, preserve the original `fetch` function, and reach ready
without the boot screen. A screenshot directory also receives `startup.json` with navigation-relative
milestones and Wasm Resource Timing entries. `HARNESS_CHROMIUM` can select an already-installed
Chromium executable.

```sh
npm --prefix scripts/ui-builder-web-smoke ci
npm --prefix scripts/ui-builder-web-smoke test
SMOKE_LANES=desktop,mobile node scripts/ui-builder-web-smoke/smoke.mjs \
  ui-builder/build/wasmDist build/web-smoke
```

`globalThis.__uiBuilderStartup` holds a bounded set of first-occurrence milestones; the same names
appear as `ui-builder:*` User Timing marks in a browser performance trace. There are no design ids,
actor ids or document contents in them, and they are not persisted or uploaded.

- `kotlin-start`, `fonts-ready`, `storage-ready`, `compose-start` separate Wasm initialisation from
  font registration, local storage hydration and starting the editor host.
- `identity-start`, `design-start`, `design-loaded` describe a hosted design's opening path; fixture
  and embedded-host modes need not report them.
- `editor-ready` is the editor host's readiness signal. `editor-paint-opportunity` is two animation
  frames later, giving it an opportunity to paint; it is not proof that a pinned preview has drawn.
- `renderer-requested`, `renderer-ready`, `preview-rendered` separately report the first pinned
  renderer's creation, validated initialisation reply and validated render reply. These can arrive
  after the boot screen is removed. An in-process fixture has no pinned renderer and no such marks.

HTTP cache hits alone do **not** prove compiled-Wasm caching. To check that separately in Chromium,
record the `disabled-by-default-devtools.timeline` trace category and look for
`v8.wasm.moduleCacheHit` on repeat visits, including a browser restart with the same profile. Keep
asset URLs stable and leave DevTools' “Disable cache” unchecked. Compare like-for-like bundles,
browser versions and devices; software WebGL and background compilation make absolute timings noisy.

The boot script must leave the original fetch Response untouched. Rebuilding it with
`new Response(stream, { headers })` preserves streaming and the MIME type but discards the network
response's compiled-code cache metadata. The phase-based indicator intentionally avoids byte-counting
interception. The shells preload the JavaScript module graph, but leave Wasm requests to the
streaming loaders: Chromium 153 created code-cache entries with `rel=preload as=fetch` Wasm links
but did not consume them on subsequent browser starts. Direct fetches consumed both module caches.
Neither a service worker nor another copy of the Wasm bytes solves that metadata loss.

## 3. The deployed editor

`https://preview.coo.ee/ui-builder/` needs WebGL (Skiko draws through it). When a browser has none,
the page says so in plain text rather than staying blank (`Main.kt`, `webGlAvailable`), so a
**stuck "Loading the editor" boot screen is not a WebGL problem**: it means the Wasm or `.mjs`
downloads did not finish. From an agent sandbox that has been the proxy dropping large or parallel
requests (`net::ERR_TOO_MANY_RETRIES`), and the page has no error or retry state for it. Playwright
needs `proxy: { server: process.env.HTTPS_PROXY }` and once still failed for the 29 MB
`uiBuilder.wasm`.

On 2026-10-01 it loaded to `data-ui-builder-ready` from a sandbox after two fixes, both needed:

- **`net::ERR_CERT_AUTHORITY_INVALID`.** Chromium ignores the proxy CA that `curl`, Node and the JVM
  are given and trusts only `~/.pki/nssdb`. coo-ee-env imports the CA there
  (yschimke/coo-ee-env#86). Never work around it with `ignoreHTTPSErrors`.
- **"The editor could not start — Incorrect locale information provided".** Under `LANG=C.UTF-8`
  Chromium reports `navigator.languages` as `["en-US@posix"]`, which `Intl.Locale` rejects. Builds
  with `normalizeBrowserLanguages` in `Main.kt` repair the tag. Against an older deployment, pass
  `locale: 'en-US'` to `newPage`.

If it still sticks at the boot screen, do not spend long on it: use option 1.

## Sandbox gotchas

1. **HTTP 429 from Maven Central** during dependency resolution, on a different artifact each time.
   It is transient burst limiting by the proxy. Retry, with `--max-workers=1`; Gradle keeps what it
   downloaded, so each attempt gets further (one needed four tries). `curl` to the same URL works,
   so this is not a reachability problem.
2. **`UnsatisfiedLinkError: libfontmanager.so … GLIBC_ABI_DT_X86_64_PLT not found (required by
   /nix/store/…/libpthread.so.0)`** at `Skiko`/`setSystemLookAndFeel`. The sandbox shell sets
   `JAVA_HOME=/root/.cache/coo-ee/jdk-gl/17`, a Nix JDK. Gradle's daemon runs on it and the app
   process inherits its Nix library path, which mixes Nix glibc into the system JDK 21 that the
   toolchain picks. Fix: `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and `./gradlew
   --stop` so the daemon restarts on it. A plain `java` AWT test passes, which is what makes this
   look like an app bug when it is not.
3. **No screenshot tools** (`xwd`, `import`, `scrot` are absent and there is no Pillow): use
   `drive.sh shot`.
4. **Fonts are DejaVu fallbacks** under Xvfb, so judge layout and behaviour, not typography.
5. **Mouse only.** Robot clicks are not touch events, so tap targets, gestures and drag-to-add
   are untested by this method.

## What a first pass found

A pass over the desktop app's compact layout (Material 3 Hello, Wear M3 screen, Weather widget,
Wear widget adaptive) found usability problems worth re-checking after related changes: the bottom
sheet covers the element being edited; tapping a button selects its inner text; the zoom control
overlaps the canvas corner; a phone design opens at 50% with unreadable text; the form factor is
reachable only from the File menu; and the Wear M3 template is nearly empty. Not covered: Layers,
drag-to-add, comments, export, touch.
