# Hosting the UI builder in the VS Code extension

**Status: handover note.** Nothing here is built. This is a feasibility assessment with the facts
checked, written so the next person can start on the right thing rather than re-derive it.

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
the last release asset the old repository published). Since this repository split its release, that
archive is an independently versioned GitHub release asset — which is exactly what an extension
build step wants to fetch, with no Gradle and no dependency on either repository's build.

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
