# The UI Builder as an MCP App (OpenAI file entrypoint)

**Status: the editor side is built** (compose-ui-builder#364). The server side, a `design_open`
tool in compose-preview-server's local MCP server, is specified at the end of this note and is not
built yet. The umbrella issue is compose-preview-server#1235. Its probe, #1236, must still confirm
that ChatGPT/Codex desktop gives a local stdio server everything below.

When somebody opens a `.uid` design in ChatGPT or Codex desktop, the host shows this editor in
place of its default viewer, and edits save back to the file. IntelliJ's
`UiBuilderProjectFileEditorProvider` and VS Code's custom editor (`HostBridgeApp.kt`) already work
this way. This is the same experience in a third host.

## The pieces

| Piece | Where | What it does |
| --- | --- | --- |
| `McpAppDesignSession` | `ui-builder/src/commonMain/.../mcpapp/` | The file rules: load, save, conflict, read-only, external change. Pure Kotlin, tested on the JVM against a fake host. |
| `McpAppBridge` / `McpAppJsonRpcBridge` | same | The seam. The session talks to an interface. The real bridge turns each call into MCP Apps JSON-RPC plus the OpenAI extensions. |
| `McpAppSelectionContext` | same | Turns the selected layer into `ui/update-model-context` content. |
| `McpAppCatalogs` | same | Resolves `catalogSystemId` to a capability catalog. |
| `UidDesignFiles` | `ui-builder/src/commonMain/` | The `.uid` codec. It is shared with the host bridge, so every host writes a design as the same bytes. |
| `McpAppHostApp` | `ui-builder/src/wasmJsMain/` | The `postMessage` transport, the file bar and the editor. Selected when the page defines `globalThis.composeUiBuilderMcpApp`. |
| MCP App shell | `ui-builder-web/src/mcp-app/ui-builder-mcp-app.html` → archive `mcp-app/ui-builder-mcp-app.html` | The `text/html;profile=mcp-app` resource a server serves. |
| Fake host harness | `scripts/ui-builder-web-smoke/mcp-app-host.mjs` | Opens the built editor inside a fake host and checks the round trip in Chromium. |

## The protocol, as the editor uses it

MCP Apps (`modelcontextprotocol/ext-apps`) is JSON-RPC 2.0 over `window.parent.postMessage`.

1. `ui/initialize` with `protocolVersion: "2026-01-26"`, then `ui/notifications/initialized`.
   The editor needs `hostCapabilities.experimental["openai/resource"]`. If the host does not
   advertise it, the editor says so and stops.
2. `ui/notifications/tool-input` carries the entrypoint's `FileInput`,
   `{ file: { name, resourceUri } }`.
3. `resources/subscribe` on `resourceUri`. This is best effort: if it fails, the file bar says
   "not following external edits".
4. `resources/read` with `_meta["openai/resource"].representation = "text"`. The response
   carries `_meta["openai/resource"]` with an `etag` and `writable`. A `blob` answer is decoded
   too, as the spec asks an app to handle.
5. The text is parsed as `DesignDocumentV1`, accepting schema `compose-ui-builder-document/v1` or
   its `-candidate`.
6. Edits autosave 1.2 s after the last change, or on **Save**. Each save is an
   `openai/resources/write` of the whole document, with `ifMatch` set to the last etag:
   - `saved`: the new etag is kept. The editor is **not** reloaded, so undo survives the save.
     Undo lives only in memory, as the local store keeps it, and the file holds the document only.
   - `conflict`: nothing is written. The file bar offers **Reload theirs**, which discards local
     edits, or **Overwrite with mine**, which writes again with `ifMatch` set to the etag the
     host reported. Autosave does not write over an unresolved conflict.
   - `too-large`: an error that shows the design's size and the host's `maxBytes`.
   - The editor never writes a file whose `writable` was not `true`. The file bar says the file is
     read-only. *The canvas itself is not locked yet: edits stay in the view and are not saved.
     A true read-only editor mode is a follow-up.*
   - A design with no edits is never written, so opening a file and closing it again leaves the
     file untouched.
7. `notifications/resources/updated` makes the editor read the file again. If the etag is the one
   the editor last saw, the notification is the echo of its own save and is ignored. If the
   design is clean, the editor adopts the new version, as a reload. If it is dirty, the file bar
   offers **Reload** or **Keep mine**. After **Keep mine**, the next save meets the external
   version as a conflict.
8. When a layer is selected, the editor sends `ui/update-model-context`, debounced by 250 ms, with:
   - a visible text block titled `_meta["openai/title"] = "<Component> · <nodeId>"`, holding the
     component, the node id, its path from the root and its properties;
   - a hidden block (`annotations.audience: ["assistant"]`) with the same facts as JSON, plus how
     to act on them: edit that node, by id, in the `.uid` file;
   - `structuredContent.selection`, the same JSON.

   When nothing is selected, the editor sends `content: []`, which clears the attachment. Repeat
   selections send nothing.
9. The host may send `ping`, which the editor answers. On `ui/resource-teardown` the editor
   unsubscribes.

## Catalogs, and what happens offline

The IntelliJ project session calls `OfflineCatalog.forSystem(document.catalogPin.systemId)`. The
editor follows the same rule. The design's `catalogPin.systemId` picks one of the catalogs the
archive **packages**: `m3-catalog`, `wear-m3` or `remote-m3`. The editor reads
`<systemId>-capabilities-v1.json` from the archive's own origin. That origin is the same one the
editor's Wasm came from, so if the editor loaded, its catalogs load too. Nothing in the flow calls a
hosted catalog. With a loopback asset origin (below), the whole editor works offline.

A design pinned to any other catalog opens with an error that names the catalog. The editor does
not open it against the wrong components. `McpAppCatalogs` accepts an optional hosted base
(`composeUiBuilderMcpApp.catalogBase`) and tries it only for unpackaged catalogs. The shell leaves
it unset today, because nothing serves arbitrary catalogs' capability JSON under a stable URL yet.

## The bundle: one small HTML document, assets under `resourceDomains`

A single self-contained HTML document is impractical. The archive is 18 MB zipped and 80 MB
unpacked, and `uiBuilder.wasm` alone is 66.6 MB (`UI_BUILDER_VSCODE_HOST.md` has the
measurements). Inlined as base64, the HTML would be about 100 MB of JSON-RPC text in one
`resources/read`. The alternative the OpenAI example uses is to fetch the Wasm through
`resources/read` of server resources as base64 blobs. That has the same cost, paid on every open,
over a stdio pipe.

So the resource is a **small shell** (about 5 KB), `mcp-app/ui-builder-mcp-app.html` in the web archive:

- `<base href="__COMPOSE_UI_BUILDER_ASSET_BASE__">` and `globalThis.composeUiBuilderMcpApp = { assetBase, version }`.
  The version is written in at build time. The asset base is the **only** placeholder a server
  fills in: an absolute URL, ending in `/`, of the unpacked archive's root.
- The same import map, preloads, boot screen and module script as `index.html`, all relative, so
  they resolve against the base.
- In this mode the editor's own requests (`sameOriginRequestUrl`) must stay under
  `assetBase`, or under `catalogBase` when it is set.

The archive manifest `ui-builder-web.json` carries `"mcpApp": 1`. That number is the contract
between the shell and the server: the placeholder's name, and the shell's path in the archive.
`verifyUiBuilderWebArchive` checks that the shell is packaged, that its placeholder is intact and
that its version was written in.

What the page needs from the host's sandbox:

- **CSP**: `resourceDomains` and `connectDomains` both list the asset origin, because the Wasm is
  fetched. `script-src` must allow WebAssembly compilation (`'wasm-unsafe-eval'`). The OpenAI
  example app runs Wasm (OpenCascade), so ChatGPT's sandbox allows it, but #1236 should record
  that.
- **CORS on the asset origin.** The frame's origin is the host's sandbox, so module scripts and the
  Wasm fetch are cross-origin: they need `Access-Control-Allow-Origin`, and `application/wasm`
  for streaming compilation. A loopback origin reached from an `https` sandbox page is a
  Private Network Access request, so the server also answers the preflight with
  `Access-Control-Allow-Private-Network: true`.
- **WebGL**, as everywhere this editor runs (`AGENT_TESTING.md`).

## What compose-preview-server's `design_open` must do

compose-preview-server has not changed. This is the whole of its side:

1. **Tool** `design_open`:
   - `inputSchema` is `FileInput`: `{ file: { name: string, resourceUri: string } }`, both
     required.
   - `_meta.ui.resourceUri: "ui://compose-ui-builder/editor"`.
   - `_meta["openai/ui"].entrypoints: [{ "type": "file", "extensions": [".uid"] }]`. It claims
     `.uid` only.
   - An SVG icon, following the spec's icon guidelines.
   - The result can be a one-line text, "Opened <name>". The app reads the file through the host,
     not through the tool. `_meta["openai/resource"].path`, which the host injects on app→server
     calls, is not needed by the editor.
2. **Resource** `ui://compose-ui-builder/editor`:
   - `mimeType: "text/html;profile=mcp-app"`.
   - `text`: the archive's `mcp-app/ui-builder-mcp-app.html`, with every
     `__COMPOSE_UI_BUILDER_ASSET_BASE__` replaced by the asset base.
   - `_meta.ui.csp: { resourceDomains: [<asset origin>], connectDomains: [<asset origin>] }`.
   - `_meta["openai/ui"]: { preferredDisplayMode: "fullscreen", availableDisplayModes: ["fullscreen"] }`.
3. **Asset origin.** Serve the same unpacked web archive the server already carries
   (`unpackUiBuilderWeb`, or a pinned editor under `ServeUiBuilderEditorStore`) over HTTP, with the
   CORS and Private Network Access headers above, `application/wasm` for `.wasm`, and long-lived
   caching under a versioned path. For the local stdio server, this is a loopback listener,
   `http://127.0.0.1:<ephemeral port>/ui-builder/v/<version>/`, started on the first read of the
   editor resource. The port goes into both the base and the CSP.
4. **Gate** on the archive's `ui-builder-web.json` having `mcpApp` equal to `1`. With an older
   archive, the server does not register the tool.
5. **Nothing else.** `resources/read`, `resources/subscribe` and `openai/resources/write` on the
   `host-resource://` URI are answered by the host, never by the server.

Local check: `node scripts/ui-builder-web-smoke/mcp-app-host.mjs ui-builder/build/wasmDist`
plays a host with three origins (host page, sandbox, asset server), the CSP shape above and CORS.
It fails unless:
- the design opens;
- nothing is written until an edit;
- a selection becomes model context;
- an edit saves with `ifMatch`;
- an external edit is picked up.
