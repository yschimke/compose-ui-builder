# Extracting the UI builder, and running it on the desktop

**Status: proposal.** Nothing here is enforced yet, and nothing here overrides
[`UI_BUILDER_PROJECT_BOUNDARY.md`](UI_BUILDER_PROJECT_BOUNDARY.md), which is normative and says the
builder stays in this repository. This document answers the question that was asked next — *if we
did extract it, and if we wanted it on the desktop, what actually has to be true* — and it answers
three specific questions about the desktop: Wasm mode, JVM mode, and the Robolectric daemon.

## 0. The short answers

| Question | Answer |
| --- | --- |
| Can we extract `:ui-builder` and consume it as a library? | Yes for the code; the cost is not the code. See §2 — this repository publishes nothing to Maven today, so an extraction is first a *publication* project and only second a *module* project. |
| Can we run Wasm mode as a desktop app? | Yes, and it is the cheapest by a wide margin: embed a Chromium view (or open a loopback URL) over the archive we already build. It buys a window, not a desktop application. §5. |
| Can we run JVM mode on the desktop? | Yes, and this is the one worth building. The editor already compiles for the JVM and is already exercised under Compose UI test on desktop Skiko. The gap is a composition root and eight host ports, not a port of the editor. §6. |
| Can we run the daemon for Robolectric Android renders? | Yes. The lane already runs off-server today (`design render --local`). The obstacles are packaging and prerequisites — `lib-daemon-android`, `android.jar`, a Kotlin compiler — not capability. §7. |

There is one architectural finding worth reading even if nothing else here is acted on: **the
desktop app's Android render lane cannot live in the extracted UI-builder repository** under rule 1
of the boundary document, because every part of that lane is server-project code. §7.4 gives the
three ways out.

## 1. What "the UI builder" is, measured

Nine modules, ~125k lines of Kotlin, against ~220k in the server project.

| Module | Targets | Lines | Role |
| --- | --- | --- | --- |
| `:ui-builder` | `jvm`, `wasmJs` | ~84k | the editor: canvas, palette, inspector, reducer, exporters, local service |
| `:ui-builder-runtime` | JVM, `explicitApi()`, pinned ABI | ~23k | the design service: state, validation, revision-pinned export |
| `:ui-builder-export` | `jvm`, `wasmJs` | ~16k | design → screen-model projection |
| `:ui-builder-generated-jetcaster` | — | ~1.5k | generated-fidelity fixture |
| `:ui-builder-reference-jetcaster` | `wasmJs` | ~0.6k | reference-fidelity fixture |
| `:ui-builder-renderer` | `wasmJs` **only** | ~0.2k | the sandboxed renderer-only runtime |
| `:ui-builder-artwork` | `jvm`, `wasmJs` | ~0.05k | offline artwork bindings |
| `:ui-builder-web` | — | — | packages `:ui-builder`'s Wasm output as an archive |
| `:ui-builder-render-bundle` | — | — | packages `:ui-builder`'s JVM previews as a polyglot bundle |

Against that, roughly **13k lines of `:server` are UI-builder glue** (44 files matching
`*UiBuilder*` / `*Design*`): the HTTP and WebSocket routes, the native-preview lane, the MCP
adapter's design tools, and the `design` CLI. That glue is the host, and it stays with the host.

The seam is genuinely clean, and the boundary document already says why. Two facts are worth adding
to its inventory:

- **Only four `expect`/`actual` declarations exist in `:ui-builder`** — two image codecs and two
  Wear pickers — and the JVM actuals are already written. The editor is not platform-entangled.
- **`:ui-builder`'s composition root is not.** `wasmJsMain/…/Main.kt` is 3,211 lines and holds the
  app shell, the routing, the host wiring and the fixture apps. Every desktop question below runs
  into this file.

## 2. The precondition nobody can skip: this repository publishes nothing

`grep -rl maven-publish` over the build returns nothing. #794 removed the Maven coordinates
deliberately, and the boundary document records the consequence: "resolvability from an external POM
is no longer a criterion" for a seam.

So "used as a library from compose preview server" is not a refactor of module edges. It is:

1. a `maven-publish` lane in the new repository, with signing and a repository the server build can
   resolve (Maven Central, as `ee.schimke.composeai:*` already is, or a GitHub-hosted raw Maven
   repository as `ee.schimke.wearcmp:*` already is — the `settings.gradle.kts` here already fences
   one of those, so the pattern is known);
2. a published **KMP** lane — `:ui-builder`, `:ui-builder-export` and `:ui-builder-artwork` have
   `jvm` and `wasmJs` variants, and Gradle module metadata has to carry both;
3. a version pin in this repository's catalog, and a bump process for it;
4. `:ui-builder-web` and `:ui-builder-render-bundle` published as **archives**, not jars — today the
   server consumes them through attribute-matched `distribution` configurations (`Category=distribution`,
   `LibraryElements=ui-builder-web`). Across a repository boundary those become classified artifacts
   the server unpacks, and the attribute matching is replaced by a coordinate plus a checksum check.

That list is the real project. The boundary document's cost estimate — "every change spanning the
editor and the routes that serve it becomes two pull requests, a release and a pin bump" — is
unchanged by anything in this document, and the `check_preview_server_pin.py` precedent it cites is
the shape of the pain.

## 3. The four seams, as coordinates

The boundary already names exactly what crosses. Extraction turns each into an artifact:

| Seam today | Artifact after extraction | Consumed by |
| --- | --- | --- |
| `project(":ui-builder-runtime")` | `ee.schimke.composeai:compose-preview-ui-builder-runtime` (jar, ABI-pinned) | `:server` |
| `project(":ui-builder-export")` | `…:compose-preview-ui-builder-export` (KMP jvm + wasmJs) | `:server`, `:ui-builder` |
| `project(":ui-builder-web")` | `…:compose-preview-ui-builder-web` (zip) | `:server` distribution |
| `project(":ui-builder-render-bundle")` | `…:compose-preview-ui-builder-render-bundle` (jar carrying `bundle.png`) | `:server` |

All four already carry `publishedArtifactId` values and a version derived from
`.release-please-manifest.json`. The artifact identities were designed for this; only the publishing
is missing.

`:ui-builder` itself stays not-a-seam. The editor is reached as a distribution, never as a
classpath — except by a desktop app, which is exactly why §6 changes the boundary's shape.

## 4. The genuinely hard part: the shared fixtures

`docs/design/fixtures/ui-builder/` is 4.5 MB of JSON that **both** projects read, and the two
readings are not the same kind of claim:

- `:ui-builder`'s build **compiles** `m3-catalog-components-v1.json` (merged with
  `compose-foundation-components-v1.json`) into `EMBEDDED_COMPONENT_RECORD_JSON`, so the editor
  judges a design against the record the server exports against.
- `:ui-builder-runtime`'s `processResources` **packages** `m3-catalog-capabilities-v1.json`.
- `:server`'s distribution **ships** `m3-catalog-components-v1.json`.
- `:server`'s tests **verify** the fixtures against the live catalog —
  `PublishedM3CatalogEquivalenceTest`, `M3CatalogComponentRecordTest`,
  `PublishedGeneratedM3CatalogEquivalenceTest`, `CatalogSourceFlipTest` and others.

That is a circular dependency of *content*: the fixture is derived from the server's catalogs and
compiled into the builder's binary, and the check that they agree lives on the server side. Split
the repositories and one of three things has to be true, and this is the decision to make first
because it determines the release choreography:

1. **The fixture moves to the builder repository and the server pins it.** The equivalence tests
   move with it and gain a dependency on the catalog they verify against — which is server-project
   content. Clean direction, awkward tests.
2. **The fixture stays here and the builder consumes it as a published artifact.** Then the
   builder's *compile* depends on a server release, and a catalog change is a two-release dance in
   the opposite direction from everything else.
3. **The fixture becomes its own published contract artifact**, versioned separately, with the
   equivalence check running in whichever repository can see both sides. This is the honest answer
   and the most work, and it is what `compose-preview-contracts` already is for the protocol.

Option 3, with the record joining the contracts repository, is the recommendation. Options 1 and 2
each make one repository unable to test its own correctness.

Beside the fixtures, the same question applies in smaller form to: the committed goldens and
`scripts/regenerate-goldens.sh`, the visual harness (`preview-harness/ui-builder-renderer.spec.mjs`),
and the two Java floors — the builder lane is Java 21, the server 17, and `:server:checkServerJvmFloor`
enforces the gap. The floors survive extraction unchanged; the check that enforces them has to be
told the artifact is now external.

## 5. Wasm mode as a desktop app

**Yes, and we nearly ship it already.** `compose-preview-server ui --no-project` starts the server,
serves the archive and opens a browser. A "desktop app" in Wasm mode is that, minus the browser
window belonging to someone else.

Two shapes:

**5a. Embedded Chromium (KCEF/JCEF) in a Compose Desktop window.** The editor is a `<canvas>` served
from loopback; the app owns the window, the menu bar and the file dialogs, and can inject the same
`?storage=local` mode the browser uses offline. Constraints, in order of how much they matter:

- **Kotlin/Wasm needs WasmGC**, which is Chromium 119+. JCEF-class runtimes are well past that, but
  the version has to be asserted at launch rather than assumed, and a stale embedded runtime fails
  as a blank canvas, which is the worst possible diagnostic.
- CEF adds 150–250 MB per platform to the distribution, and it is per-platform, so the "one portable
  tarball" property the server distribution deliberately protects (`checkServerDesktopSidecarPackaging`
  rejects a host-specific Skiko) is lost for the desktop artifact.
- The renderer sandbox survives unchanged: `mountSandboxRenderer` puts `:ui-builder-renderer` in an
  iframe, and an iframe inside CEF is still an iframe.

**5b. No embedded browser at all** — a native launcher that starts the local service and opens the
system browser. This is what exists. It is not worse for a developer and it is worse for everything
that makes a desktop app feel like one: no native file dialogs, no drag-in from Finder, no
recent-documents, no single-instance behaviour, no offline story that a user believes.

The real verdict on Wasm-as-desktop: it is the right *interim* answer and the wrong *destination*.
It adds no new code paths — which is its whole appeal — and it also adds no new capability. Crucially
it does **not** get you §7: an embedded browser cannot open a Robolectric daemon, so a Wasm desktop
app still needs a JVM process beside it, at which point the JVM process may as well draw the window.

## 6. JVM mode on the desktop

**Yes, and this is the one to build.** The evidence that the editor already runs on the JVM is not
speculative:

- `:ui-builder` has a `jvm()` target and compiles the whole editor for it today.
- `jvmTest` drives the editor through `compose.uiTest` against `compose.desktop.currentOs` — real
  Skiko, real composition, asserting on real editor behaviour (#619).
- `jvmMain` already holds `FileLocalDesignStorage`, the JVM image codecs, the Wear picker actuals,
  and a Skia SVG recorder.
- `LocalUiBuilderService` + `LocalUiBuilderHttpTransport` are **common** code: the full v1 protocol,
  in-process, behind the same `UiBuilderHttpTransport` seam the browser uses over HTTP
  ([`UI_BUILDER_LOCAL_STORAGE.md`](UI_BUILDER_LOCAL_STORAGE.md)). A desktop app binds that transport
  and has a working, offline, undoable editor with no server at all.
- The Wear CMP port publishes a `jvm` variant alongside `wasmJs`, so the Wear canvas is not lost.

### 6.1 What is actually missing

1. **A composition root.** `Main.kt` (3,211 lines) is `wasmJsMain`. The app shell, the mode routing,
   the host wiring and the fixture apps all live there, and none of it is browser-specific by
   nature — it is browser-specific by address. This is the single largest piece of work and it is a
   *move*, gated by the existing `jvmTest` suite, not a rewrite. Split it into a common
   `UiBuilderApp(hosts: UiBuilderHosts)` plus a thin per-platform entry point.
2. **Eight host ports.** The browser implementations are already behind interfaces, which is what
   makes this tractable:

   | Browser | Interface | Desktop implementation |
   | --- | --- | --- |
   | `BrowserLocalDesignStorage` | `LocalDesignStorage` | `FileLocalDesignStorage` — exists |
   | `BrowserUiBuilderHttpTransport` | `UiBuilderHttpTransport` | `LocalUiBuilderHttpTransport` (offline) or Ktor client (attached server) |
   | `BrowserUiBuilderWebSocketTransport` | protocol update client | Ktor client WebSocket; unused offline |
   | `BrowserMaterialSymbolsTransport` | `MaterialSymbolsTransport` | HTTP + on-disk cache |
   | `BrowserCatalogRuntimeManifestTransport` | `CatalogRuntimeManifestTransport` | local file or HTTP |
   | `BrowserExportHost` | export actions | native save dialog + `:ui-builder-runtime` in-process |
   | `BrowserReferenceHost` | reference images | native open dialog + local store |
   | `BrowserCommentHost` | comments | server-only; degrade honestly when detached |
   | `BrowserNativeStream` | `UiBuilderNativeStream` | §7 |

3. **The preview pane's host call.** `RemoteDocumentPreviewPane` is common and plays a RemoteCompose
   document through `RcComposePlayer`, but it takes `request: suspend (UiBuilderDocument) -> …` from
   the host, and today only the server answers it. On the desktop this is either a call into a
   locally-linked export or a call to an attached server.
4. **No renderer archive.** `:ui-builder-renderer` is `wasmJs`-only and has no JVM target. On the
   desktop the sandboxed-iframe indirection is unnecessary — the canvas composes in-process — so the
   correct move is *not* to port the renderer module but to make the preview pane's rendering
   strategy a host decision. That does cost one thing the iframe buys: the measurement protocol and
   the editor-marker isolation `verifyRendererRuntime` checks for.

### 6.2 What this gets you that Wasm does not

Native file dialogs and drag-in for reference images. A real offline mode a user can trust. Menu
bar, shortcuts and window management. A single process that can also hold the render daemon (§7).
And one distribution artifact per platform via `compose.desktop.application` with `jpackage`, with
the Java 21 floor satisfied by the bundled runtime instead of asserted at startup and apologised
for — which is what [`UI_BUILDER_GETTING_STARTED.md`](../UI_BUILDER_GETTING_STARTED.md) has to do
today.

### 6.3 Where it lives

`:ui-builder-desktop` in the extracted repository, depending on `:ui-builder` as a classpath. Note
that this is the first consumer of `:ui-builder` as a library rather than as a distribution, so rule
2's "the editor is reached as a distribution, never as a classpath" acquires an in-project exception.
Inside the builder project that is unremarkable; it matters only because §7 wants to cross back.

## 7. The daemon, and Robolectric Android renders

**Yes — and more of it already works off-server than one would guess.**

### 7.1 What exists

`DesignLocalLane` / `DesignLocalRunner` exist precisely so a design can be compiled and rendered
**in-process, with no server**, for debugging (#544, #551, #481). It wires the same
`ScreenGeneratorComposeExportExecutor` → `UiBuilderGeneratedPreviewAdapter` →
`PlaygroundCompileService` → daemon path the server drives, deliberately, so that it reproduces the
server's bugs rather than resembling them.

`PlaygroundDaemonOpeners` already offers both backends, and the selection is one injected function:

- `android(...)`: `lib-daemon-android` + `android.jar` on the daemon classpath, Robolectric jvmArgs
  and system properties, subprocess `openBundleDaemon`.
- `desktop(...)`: `lib-daemon-desktop` + `lib-renderer`, desktop jvmArgs, same subprocess shape.

`PlaygroundAndroidRenderService`'s own KDoc states the property that matters here: the flow is
backend-agnostic — open, render, read the PNG — and only the sidecar differs. A desktop app that can
open one can open the other, and `ServeUiBuilderNativePreview` already explains *why* you would
bother: Robolectric-backed Android rendering, platform text metrics and device frames are the
question the Wasm canvas structurally cannot answer.

### 7.2 What a desktop app must carry or find

| Prerequisite | Source today | On the desktop |
| --- | --- | --- |
| Java 21+ | user's `JAVA_HOME`, apologised for at startup | bundled runtime via `jpackage` — solved |
| `lib-daemon-desktop` + `lib-renderer` | staged into the server distribution by `stageDaemonDesktopLibs` / `stageRendererLibs` | same staging, into the app image |
| `lib-daemon-android` | **ships separately** as `compose-preview-android-daemon-<version>.zip`, located via `-Dcomposeai.cli.libDaemonAndroidDir` | bundle it, or fetch-on-first-use into a cache |
| `android.jar` | `ANDROID_HOME` / `ANDROID_SDK_ROOT` | same; there is no bundling this one, and the licence is why |
| Robolectric `android-all` jars | fetched from Maven at first use, cached | first Android render needs network; say so before it hangs |
| Kotlin compiler | `kotlin-build-tools-api` + `kotlin-compose-compiler-plugin-embeddable`, staged as `composePreviewBta` | the largest single payload; unavoidable, because the lane compiles generated Kotlin |
| The render bundle | `:ui-builder-render-bundle`'s `bundle.png` | same artifact |

None of these is a blocker. Together they are the reason the Android lane should be a **capability
the app detects and reports**, exactly as `PlaygroundDaemonOpeners` already does — it returns null
having said why — rather than a feature the installer promises.

### 7.3 Skiko/desktop renders are the easier half

The CMP desktop daemon needs no Android SDK, no Robolectric and no network. A desktop app can offer
*that* lane on day one and treat the Android lane as the opt-in that needs an SDK. Worth stating
plainly because "run the daemon" collapses two very different setup costs.

### 7.4 The boundary problem, which is the real finding

Every piece of §7.1 is **server-project code**: `PlaygroundCompileService`, `PlaygroundDaemonOpeners`,
`PlaygroundSandbox`, `UiBuilderGeneratedPreviewAdapter`, `ScreenGeneratorComposeExportExecutor`,
`DesignLocalLane`. Rule 1 of the boundary document — *the builder never names the server* — means a
`:ui-builder-desktop` module in the extracted repository **cannot depend on any of it**. Three ways
out, in the order I would prefer them:

1. **Extract the render lane too, into a third published artifact** (`compose-preview-render-lane`,
   or a module of the contracts repository) that both the server and the desktop app depend on. It
   is a coherent unit — compile generated Kotlin, open a daemon, return a frame — with no HTTP, no
   design state and no editor in it. Most work, best result, and it is the only option that leaves
   both hosts driving literally the same lane, which is the property #551 bought.
2. **The desktop app stays in this repository**, depending on the published builder artifacts and on
   `:server`'s lane. The boundary holds unchanged; the desktop app is a server-project module that
   happens to draw an editor. Cheapest, and it splits the desktop story across two repositories.
3. **The desktop app shells out** to `compose-preview-server design render --local`, treating the
   render lane as a subprocess CLI contract rather than a classpath. Honest, weakly typed, and it
   makes the CLI's local mode a supported interface rather than a debugging aid.

Option 2 is the right first move and option 1 is the right destination. Option 3 is worth keeping in
mind as the thing that makes a prototype possible next week.

## 8. A staged plan

Each stage is separately valuable and separately abandonable. Nothing before stage 4 requires
deciding whether the extraction happens.

**Stage 1 — Make the JVM editor real (this repository, no extraction).**
Hoist `Main.kt`'s shell into `commonMain` behind a `UiBuilderHosts` interface; write the desktop
actuals; add `:ui-builder-desktop` with `compose.desktop.application`; bind
`LocalUiBuilderHttpTransport` + `FileLocalDesignStorage`. *Gate:* the existing `jvmTest` suite passes
unchanged against the hoisted shell, and the app opens, edits, undoes and saves a design with no
server running.

**Stage 2 — Give the desktop app the desktop daemon.**
Stage `lib-daemon-desktop` + `lib-renderer` + the render bundle into the app image; drive
`DesignLocalLane`'s shape from the app (option 2 or 3 of §7.4). *Gate:* a design renders a native
first frame in the app with no server and no Android SDK.

**Stage 3 — Add the Android/Robolectric lane as a detected capability.**
Locate or fetch `lib-daemon-android`, resolve `android.jar`, report every missing prerequisite by
name the way `PlaygroundDaemonOpeners` already does. *Gate:* the Jetcaster benchmark design renders
through Robolectric in the app, and every missing prerequisite produces a sentence rather than a
blank pane.

**Stage 4 — Decide the fixture question (§4).**
This is a decision, not code, and it blocks stage 5. Recommendation: the component record and
capability fixtures join `compose-preview-contracts`.

**Stage 5 — Publish, then extract.**
Add `maven-publish` to the four seam modules *here* first and have `:server` consume the published
coordinates from this same repository. That flushes out the KMP metadata, archive-classifier and
version-pinning problems while a single `./gradlew build` can still prove both halves. Only then
move the directories. *Gate:* `:server` builds against published coordinates with no `project(":ui-builder-*")`
declaration left, and `ui-builder-project-boundary.sh` is replaced by the pin check.

**Stage 6 — Extract the render lane (§7.4 option 1), if stages 2–3 proved it worth it.**

## 9. What I would not do

- **Do not extract first and fix the fixtures later.** §4 is the only part of this that can leave
  both repositories unable to test themselves, and it is invisible until the split.
- **Do not build the CEF desktop shell as a destination.** §5 is a useful week and a dead end: it
  cannot host the daemon, so it defers every hard question while adding a 200 MB per-platform
  artifact.
- **Do not port `:ui-builder-renderer` to the JVM.** The sandboxed iframe exists because the browser
  needs isolation between the editor and the rendered document. In one JVM process it is a
  reimplementation of composition with none of the isolation that justified it.
- **Do not let the desktop app reimplement the render lane.** That is precisely the failure #551
  exists to prevent, and a second implementation would be discovered by rendering differently rather
  than by failing.
