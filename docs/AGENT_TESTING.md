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
  It holds font and identity responses independently to prove they overlap with IndexedDB startup,
  while composition waits for fonts and design requests wait for identity. A 401 still blocks the open.
- `cache` — starts Chromium three times with the same temporary disk profile and checks the second
  and third visits consume compiled-code cache entries for both Wasm modules. The profile is removed
  afterwards; `wasm-cache.json` and per-visit Wasm traces are kept beside the screenshots.
  This lane alone sets `--wasm-caching-timeout-ms=0`: it tests whether the asset/Response path
  preserves compiled-code caching, independently of V8's workload-dependent write debounce.
  Chromium 141 can postpone Skiko's cache write beyond the test's three-second settling window.
  Both modules must still hit the real disk code cache on both warm browser restarts. Ordinary
  startup lanes retain browser defaults; controlled cache-lane timings are not production benchmarks.

In a sandbox, Playwright's Chromium may already be installed (for example under `/opt/pw-browsers`
with a global `playwright` package); link that package into a `node_modules` above the script
rather than running `npx playwright install`.

### Cached startup measurements

The capture fixtures use the same coalesced inspection publisher as saved designs. Repeated
whole-document encoding in each node-layout callback otherwise inflates the fixture timing.
Font registration still precedes composition because Wear caches its first resolved typeface.
The [startup-overlap comparison](evidence/overlapping-startup/comparison.json) exercises the saved-design
path with local responses and with 500 ms delays on both identity and font responses. Its mock host
does not serve the project artwork routes; the before/after scene is pixel-identical.

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
  font registration, local storage hydration and starting the editor host. For live sessions, storage hydration and
  identity lookup begin alongside fonts; their completion marks need not follow the font mark.
- `identity-start`, `identity-ready`, `design-start`, `design-loaded` describe a hosted design's opening path; fixture
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

### Memory per screen

`scripts/ui-builder-web-smoke/memory.mjs` opens `/ui-builder/designs` and one design per catalog
against a stand-in host (identity, catalogs, the design list, `openDesign` snapshots of the
committed `site/designs/*.uid`, plus a small A2UI design) and samples each in a fresh headless
Chromium: once at `editor-paint-opportunity` and again after 8 s, both after a forced GC. It reads
the JS heap from CDP (Kotlin/Wasm GC objects live there) and per-process PSS from
`/proc/<pid>/smaps_rollup` (the renderer's figure also holds compiled Wasm and Skia's linear
memory). Send its output to a file: through a pipe nothing appears until the end.

```sh
MEMORY_RUNS=3 node scripts/ui-builder-web-smoke/memory.mjs ui-builder/build/wasmDist build/web-memory > memory.log
```

Medians of three runs after settling, 2026-10-07 (after icons moved to data, #522), Chromium 141
headless shell with SwiftShader, from [`evidence/web-memory/memory.json`](evidence/web-memory/memory.json)
(each screen's three renderer figures agreed within 2 MB):

| Viewport | Screen | JS heap | Renderer PSS | GPU PSS | Total PSS |
| --- | --- | ---: | ---: | ---: | ---: |
| desktop 1400×900 | `about:blank` (floor) | 0.2 MB | 46 MB | 27 MB | 133 MB |
| desktop | Designs (home) | 24 MB | 165 MB | 77 MB | 305 MB |
| desktop | Material 3 · Gmail tablet (158 nodes) | 38 MB | 190 MB | 87 MB | 340 MB |
| desktop | Wear M3 · Google Home (22 nodes) | 29 MB | 173 MB | 93 MB | 329 MB |
| desktop | RemoteCompose · Weather widget (5 nodes) | 33 MB | 171 MB | 91 MB | 330 MB |
| desktop | A2UI · reservation (9 nodes) | 21 MB | 156 MB | 83 MB | 303 MB |
| mobile 412×915 @2.625 | `about:blank` (floor) | 0.2 MB | 46 MB | 27 MB | 134 MB |
| mobile | Designs (home) | 24 MB | 180 MB | 95 MB | 338 MB |
| mobile | Material 3 | 31 MB | 189 MB | 113 MB | 365 MB |
| mobile | Wear M3 | 26 MB | 181 MB | 116 MB | 359 MB |
| mobile | RemoteCompose | 32 MB | 183 MB | 103 MB | 354 MB |
| mobile | A2UI | 20 MB | 170 MB | 117 MB | 351 MB |

What it says:

- The editor costs about 120 MB of renderer memory before any design is open: the home screen is
  165 MB against a 46 MB blank page. That is the 15 MB `uiBuilder.wasm` and 8.6 MB `skiko.wasm`
  compiled, plus Skia, not designs.
- An open design adds 0–35 MB total PSS on top of the home screen once it settles (A2UI, which
  draws outline stand-ins, settles just below it).
- Opening one costs more than that for a few seconds, and that peak is V8, not the editor: the
  memory it holds while TurboFan optimises a hot Wasm function, released when the compile ends.
  Binaryen inlines a one-caller function whatever its size, so it had folded Material 3's ~100
  locale tables into one 328 KB `getTranslation` and every icon builder into one 686 KB
  function. A Material 3 design calls `getTranslation` often enough to be tiered up, and TurboFan
  held 312 MB for six seconds on it: the renderer peaked at 430–520 MB around 8 s, long after the
  editor said it was ready. `wasmNoInlinePatterns` in `ui-builder/build.gradle.kts` keeps those
  tables out of line (`wasm-opt` matches `*`, and the rules must come before the optimisation
  passes). The longest TurboFan compile on that screen is now 0.3 s holding 86 MB, the renderer
  peaks at ~312 MB as the design first draws, and the module grows by 25 KB (0.08%). A global cap
  (`--one-caller-inline-max-function-size`) is no substitute: at 1000 it left the icon lookup whole
  at 697 KB, and a Material 3 design then peaked at 2.4 GB while V8 compiled it for 12 s.
- Wear M3 still peaks at ~390 MB at about 2.5 s for the same reason: Wear Material 3's own
  `<init properties GeneratedResources.kt>` is one 146 KB map literal (6,630 string literals) that
  V8 optimises even though it runs once, holding 170 MB for ~1.4 s. No pattern splits it; its
  growth is helpers like `kotlin.to` inlined thousands of times.
- Two checks keep the inlining fix from regressing. `wasm-functions.mjs` fails when any function
  in the shipped `uiBuilder.wasm` is over 200 KB (the largest is that 146 KB initialiser); and
  `MEMORY_MAX_TURBOFAN_ZONE_MB=128` makes this harness launch Chromium with
  `--trace-wasm-compilation-times` and fail when one TurboFan compile holds more. CI runs both,
  the second on the Material 3 screen. Pull requests package the module without Binaryen
  (`-PuiBuilder.wasmOpt=false`), so both can only fail on `main`. To name a function the checks report, rebuild with
  Binaryen's names kept: run `wasm-opt` on `build/compileSync/wasmJs/main/productionExecutable/kotlin/uiBuilder.wasm`
  with the Kotlin plugin's arguments (`BinaryenConfig`) plus the rules and `-g`; function indices
  match the shipped module.
- RemoteCompose used to be the largest settled desktop screen (422 MB total PSS through Playwright)
  despite five nodes. Its text "75° ☀️" has a glyph no bundled font carries, so Compose downloads a
  Noto Color Emoji slice from `fonts.gstatic.com` and installs it in the editor's font resolver.
  The device previews each ran a scene with a resolver of their own, never saw the font, kept
  reporting the glyph as unresolved, and the same 200 KB file was fetched 116 times in ten
  seconds, each copy held.
  `DeviceSceneHost` now hands its scene the editor's resolver: one download, the emoji draws in
  the previews too, and the screen settled at 377 MB (330 MB in the table above, measured without
  DevTools and after the icons moved). Run with `MEMORY_RESOURCES=1` and count the
  `fonts.gstatic.com` entries to check that it stays one.
- A2UI draws outline stand-ins on the canvas (there is no Wasm A2UI renderer), so it is the floor
  for an open design rather than a like-for-like comparison.
- Mobile shifts memory to the GPU process (+20–30 MB): the canvas backing store is 9.9 MB at
  device pixel ratio 2.625 against 4.8 MB on desktop.

- Material icons are data in the browser, not code. `material-icons-extended` was 58% of
  `uiBuilder.wasm`'s code: ~11,400 compiled `ImageVector` builders in every tab. The JVM keeps
  them; the Wasm build draws the same vectors from `icons/icons-N.json` beside the bundle (64
  files, 0.8 MB gzipped in all, ~12 KB each), fetching a file the first time one of its icons is
  drawn (`MaterialIconData`, `GoogleMaterialIconVectors`). `uiBuilder.wasm` went from 30.4 MB to
  15.0 MB (6.4 → 4.0 MB gzipped); `MaterialIconDataTest` holds every icon's data equal to its
  compiled vector, and the harness screenshots are pixel-identical before and after.

**The harness drives Chromium over a bare DevTools socket** (`cdp.mjs`), not Playwright, and
enables no domain that keeps data on the page's behalf. Playwright enables the Network domain on
every page, and DevTools then keeps each response body under its per-resource limit in the
renderer's buffer partition: `skiko.wasm` and the scripts, and the whole module once it is small
enough to qualify. Measured on the same build, that put 15–20 MB of renderer PSS on every screen
above (designs 182 MB through Playwright against 165 MB here), and it is why moving the icons to
data looked neutral through Playwright. The launcher runs the headless shell Playwright would, with
Playwright's switches, so the two differ by what DevTools retained, not by the browser. Use
Playwright for driving the editor; use this harness, or `cdp.mjs`, for a figure.

The stand-in host has no WebSockets, comments, reviews, suggestions, folders, thumbnails or
component records, so the editor shows "Disconnected" and those panels are empty; a real host's
figures are an upper bound on these. Software GL also means the GPU column is SwiftShader's, not a
real GPU's.

### Budgets

`scripts/ui-builder-web-smoke/budgets.json` caps what the editor ships and what it holds, and CI
enforces it on `main` only: pull requests package the module without Binaryen, so neither their
sizes nor their memory are the shipped ones. Sizes are checked raw and gzipped for
`uiBuilder.wasm`, `skiko.wasm`, the scripts and the whole archive (`node budgets.mjs <wasmDist>`);
memory is the settled median of three runs of the desktop designs screen and Material 3 design,
renderer PSS and JS heap (`MEMORY_BUDGETS=budgets.json` on `memory.mjs`). An MB there is 2²⁰ bytes,
as everywhere in these scripts. The caps sit 3% (sizes) and 8–15% (memory) above the 2026-10-07
figures, so a regression the size of the DevTools buffers above fails and run-to-run noise does
not. When a change knowingly spends the headroom, raise the cap in the same pull request and say
why; when one wins memory back, lower it, so the next regression cannot spend the win unseen.

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
