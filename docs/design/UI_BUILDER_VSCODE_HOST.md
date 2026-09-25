# Hosting the UI builder in the VS Code extension

**Status: shape A is built, as early access, in compose-preview-vscode.** The rest of this note is
the feasibility assessment it started from, kept because its reasoning still explains the choices.
What was built, and how it differs from the plan below:

- **It paints.** The spike's control never painted because of its full-Chromium
  `--use-angle=swiftshader` launch, not because of software GL. Playwright's headless shell, as the
  server's UI Builder harness launches it, paints in ~3 s. `spikes/ui-builder-wasm/bridge.mjs` over
  there is the instrument: it serves the extension's real webview page and the archive from two
  origins and plays the host's side of the bridge.
- **The design is the IDE's file, not the page's storage.** Rather than `?storage=local` bridged to
  host storage (§A.3), the editor has a host-bridge mode (`HostBridgeApp.kt`), selected when the
  page defines `globalThis.composeUiBuilderHost`. The host sends `open` with a `DesignDocumentV1` and
  its catalog's capability JSON, and the editor sends back `changed` with the whole document after
  each edit. In VS Code that document is a custom *text* editor's TextDocument, so dirty state,
  save, revert and diffs are the IDE's, which is the same place `OfflineUiBuilderSession.projectDocument`
  puts them for the JVM hosts. Nothing in that mode fetches, which retires open question 2.
- **Split like the IntelliJ plugin.** The editor tab is the `editor` role: Editor pane only, with the
  inspector. The `preview` role (Preview pane only) runs in a side-bar view that follows the focused
  design, as IntelliJ's Preview tool window does. The layer tree is a native VS Code tree, driven
  through `select` / `selection` messages.
- **Versioned.** `ui-builder-web.json` carries `hostBridge` (1). A host refuses an archive without
  it. The archive also packages `wear-m3` and `remote-m3` capabilities now, so a host can open a
  design in any of the three offline catalogs.
- **Not in the VSIX.** The extension downloads a pinned release (sha256-checked) or uses a local
  checkout's archive. Shape B, the server with native renders, is still the next step.

The question was: could `yschimke/compose-preview-vscode` include the UI builder? Yes. The rest of
this says which of two shapes to build, what each costs, and the one unknown that decides whether
either is possible at all.

## What the extension is today

Checked against the repository, not assumed:

| | |
| --- | --- |
| VS Code engine | `1.135.0` — recent, so its Electron is far past the WasmGC floor (Chromium 119) |
| How it renders | drives the Gradle Tooling API, spawns a JVM daemon (`composePreviewDaemonStart`), renders `@Preview` to PNG, loads the PNGs into webviews |
| Webviews | already five panels — `previewPanel`, `historyPanel`, `fontBrowserPanel`, `bundleViewerPanel`, plus the extension host |
| CSP | strict everywhere: `default-src 'none'; script-src 'nonce-…'` |
| Wasm | none, anywhere |
| UI builder | no mention, anywhere |
| `compose-preview-server` | **not** run — the extension talks to Gradle and a daemon, never to the server distribution |

The editor archive is **18.4 MB zipped** (measured from `compose-preview-ui-builder-web-3.39.0.zip`,
the last release asset the old repository published; the spike below re-measured a current one at
18.1 MB).

**How the extension gets it: a checkout and a combined build, not a release.** This note originally
reasoned from the archive being an independently versioned release asset, which an extension build
step could fetch with no Gradle and no dependency on either repository's build. That is where this
should end up, but it is not where it starts, and the decision is explicit: the extension builds
the archive from a checkout of this repository first — `:ui-builder-web:webArchive` — and a
published asset is a later step, if it happens at all. Two things follow. Consuming the editor does
not wait on a release train or a version pin between the repositories, so the spike could start
immediately. And this repository has not published a release yet — the first is being readied at
3.26.0 (#10) — so at the time the spike was written, "fetch the release asset" described an
intention rather than an available path.

## The spike that gates everything

**Does Kotlin/Wasm paint inside a VS Code webview?** Nothing else in this note matters until that is
answered, and it cannot be answered by reading.

Electron is well past WasmGC, so the language runtime is not the risk. The risk is the CSP and
Skiko: the archive needs `'wasm-unsafe-eval'` in `script-src`, a `connect-src` that permits fetching
`uiBuilder.wasm` and `skiko.wasm`, `worker-src` if Skiko spawns one, and a `frame-src` if the
sandboxed renderer iframe is wanted. Against `default-src 'none'`, each of those is an explicit
addition, and whether the set is sufficient is an empirical question.

Half a day: unpack the archive into a throwaway panel, point a `<base href>` at
`webview.asWebviewUri(distRoot)`, widen the CSP, and see whether it paints. **Do this before
anything else.** If it does not paint, both shapes below are dead and the desktop routes in
[`UI_BUILDER_EXTRACTION_AND_DESKTOP.md`](UI_BUILDER_EXTRACTION_AND_DESKTOP.md) §5–§6 are the only
ones left.

### The spike now exists, and is not throwaway

It lives in the extension's repository, at
[`spikes/ui-builder-wasm/`](https://github.com/yschimke/compose-preview-vscode/tree/main/spikes/ui-builder-wasm)
(compose-preview-vscode#31): it stages the distribution, serves it with a webview's HTML shape —
`<meta http-equiv="Content-Security-Policy">`, a per-load nonce on every script, a `<base href>` —
takes the CSP as a parameter, and measures whether a canvas painted rather than eyeballing it. Its
`selftest.mjs` checks the instruments before anything trusts them.

**It stages from a checkout, not from a Maven release.** The archive is built by this repository's
own `:ui-builder-web:webArchive` and unpacked; the plan is a combined build first, and a published
asset only later, if ever. So a version pin between the two repositories is not a prerequisite for
starting.

**The verdict is still open, and the answer is not "no".** Two things came out of it:

1. **The predicted CSP list is missing a directive.** `index.html` carries an inline
   `<script type="importmap">` (it maps `@js-joda/core` to a relative file) as well as the
   `uiBuilder.mjs` module. Under a nonce-only `script-src` that importmap is an inline script like
   any other: unnonced it is blocked, the bare specifier then fails to resolve, and **the symptom is
   a module-resolution error rather than a CSP report naming the importmap**. Nonce every script
   tag, not just the module ones.
2. **A software-GL container cannot answer the question.** With *no CSP at all*, the editor ran for
   15 minutes without a first frame, a renderer pegged at ~100% CPU throughout — executing, not
   erroring. The CSP variants were deliberately not run on top of that, because a control that
   cannot paint makes any CSP result meaningless. Rule out 66 MB of WasmGC plus Skiko against
   software WebGL on four cores before concluding anything: run it where there is hardware GL,
   which is what a webview on a real desktop has.

The measured archive, while there: **18.1 MB zipped, 80 MB unpacked, 52 files**, of which
`uiBuilder.wasm` is 66.6 MB and `skiko.wasm` 8.6 MB. §"Open questions" asks about VSIX size below;
the sharper number is the 80 MB on disk after install, not the 18 in the package.

The other half — a **real** VS Code webview, with its `vscode-webview://` origin, `asWebviewUri`
rewriting and `localResourceRoots` — remains unrun: `@vscode/test-electron` needs to download a VS
Code binary, and the sandbox the spike was written in denies that host.

## Shape A — bundle the archive, run offline

Ship the Wasm distribution inside the extension and open it in a webview with `?storage=local`.

**What it needs**

1. CSP additions, as above.
2. `<base href>` from `asWebviewUri` plus `localResourceRoots` — the archive's URLs are relative and
   a webview rewrites nothing for you.
3. A `LocalDesignStorage` bridged to extension-host storage rather than browser storage. The
   interface already exists and `BrowserLocalDesignStorage` is one implementation; a webview's
   storage is per-panel and wiped, so designs must cross `postMessage` to the host and land in the
   workspace or `globalStorage`.

**What it loses**, and these are already documented in
[`UI_BUILDER_LOCAL_STORAGE.md`](UI_BUILDER_LOCAL_STORAGE.md) as the four things offline mode cannot
do: SVG/PNG export, the native Compose render, comments, and reference images. The editing loop
itself — palette, canvas, inspector, layers, undo, the Code pane, the problems panel — is
computed in the page and needs nothing.

**Cost**: ~18 MB in the VSIX, no Java, no ports, no lifecycle.

## Shape B — spawn the server, point a webview at it

Run `compose-preview-server ui --no-project` and open a webview onto `http://127.0.0.1:<port>/ui-builder/`.

**What it needs**: the server distribution shipped or located; Java 21 for export (the distribution
targets 17 but the render bundle is compiled for 21); port and process lifecycle; `asExternalUri`
for remote and Codespaces; and a CSP permitting the loopback frame.

**What it gets**: all four of the things shape A loses, including the Robolectric-backed Android
render — which is the same machinery the extension already drives for `@Preview`.

## Which to build

**B, with A as the no-Java fallback.** Two reasons, and the second is the stronger:

1. The extension's entire existing value is "render Compose through a daemon". The UI builder's
   native-render lane is that same lane. Shape A gives a design tool that cannot answer the question
   the extension exists to answer.
2. It already spawns and supervises a JVM. Shape B is a second process of a kind it manages today,
   not a new capability.

## Why this is cheaper than the desktop shell

[`UI_BUILDER_EXTRACTION_AND_DESKTOP.md`](UI_BUILDER_EXTRACTION_AND_DESKTOP.md) §5 rejects
Wasm-as-desktop on two grounds: 150–250 MB of CEF per platform, and an embedded browser cannot host
a render daemon. **A VS Code host voids both.** The Chromium is already installed, and shape B pairs
the webview with a JVM the extension is already spawning. On those two counts the extension is a
better host for "Wasm editor plus native renders" than a bespoke desktop shell would be.

That does not make §6's JVM Compose Desktop editor redundant — it remains the only shape that runs
without a browser engine at all — but it does mean §5 reads more pessimistically than the facts now
support, and should be amended when someone touches it.

## Open questions, in the order they bite

1. **Does it paint?** The spike above. Everything else is contingent.
2. **Does anything fetch an absolute path in offline mode?** `BrowserRequestUrl` and
   `catalogAssetPath` build `/ui-builder/...` paths. `?storage=local` is documented to work from
   cache, but a webview has no origin serving those, so any escape is a 404 rather than a fallback.
3. **VSIX size.** 18 MB compressed is unremarkable for the marketplace, but confirm against the
   extension's current size and its publishing limits.
4. **Which shape the renderer iframe takes.** The preview pane plays a RemoteCompose document
   through `RcComposePlayer` and takes its content from the host — in shape A there is no host to
   ask.

## What this note does not cover

The extension's own architecture beyond what is tabulated above; this was read from the outside, in
one pass, to answer a feasibility question. Anyone building this should read
`src/previewPanel.ts` for the webview and CSP idiom the repository already uses, and follow it
rather than the sketch here.
