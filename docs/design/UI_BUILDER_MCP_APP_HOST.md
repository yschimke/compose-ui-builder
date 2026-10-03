# The UI Builder as an MCP App (OpenAI file entrypoint)

**Status: the editor side is built** (compose-ui-builder#364), with the focused canvas, Quick
edit and Comment of compose-ui-builder#374. The server side, a `design_open` tool in
compose-preview-server's local MCP server, is specified at the end of this note and is not built
yet. The umbrella issue is compose-preview-server#1235. Its probe, #1236, must still confirm
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
| `McpAppNodeComment` | same | Turns a comment on a node into one `ui/message` and one `ui/update-model-context`. |
| `McpAppLayout` | same | `focused` or `full`: which editor the panel shows, parsed from the shell. |
| `McpAppEditorScreen` | same | The file bar and the editor under it in the chosen layout. Common code, so the layout switch and the comment are tested on the JVM. |
| `FocusedCanvasUiBuilderChrome` | `ui-builder/src/commonMain/.../editor/` | The focused layout, as a `UiBuilderChrome` the host passes the way IntelliJ passes `JewelUiBuilderChrome`. |
| `McpAppCatalogs` | same | Resolves `catalogSystemId` to a capability catalog. |
| `UidDesignFiles` | `ui-builder/src/commonMain/` | The `.uid` codec. It is shared with the host bridge, so every host writes a design as the same bytes. |
| `McpAppHostApp` | `ui-builder/src/wasmJsMain/` | The `postMessage` transport, autosave and the selection's model context around `McpAppEditorScreen`. Selected when the page defines `globalThis.composeUiBuilderMcpApp`. |
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
9. **Comment** on a node (see below) sends `ui/update-model-context`, then `ui/message`. The
   node menu offers it only when `ui/initialize` advertised `hostCapabilities.message`.
10. The host may send `ping`, which the editor answers. On `ui/resource-teardown` the editor
    unsubscribes.

## The focused canvas, and the full editor

In ChatGPT or Codex desktop the file opens in a side panel next to the chat, not in a window. The
desktop editor's catalog, layers, theme and device-preview docks are built for a window, and in a
chat a big change is better asked of the agent anyway. So the panel opens on the **focused
canvas**:

- The canvas fills the panel. No toolbar, no rails, no docks, no Preview or Native pane, no status
  bar.
- Above it is the host's slim **file bar**: the file's name, its saved state, conflict and
  read-only notices, **Save**, and **Full editor**, which switches to the whole desktop editor.
  There the same button reads **Focused canvas** and switches back.
- The switch is live. The focused layout is a chrome (`FocusedCanvasUiBuilderChrome`), handed to
  the same `UiBuilderEditor` call rather than a second editor, so switching keeps the selection,
  the zoom and the undo history. Only a reload of the file starts a new editor, as before.
- What stays on the canvas is what pointing at a design needs: selection, the node's context menu,
  inline text editing (double-click a label), the editor's keys (undo, delete, copy, paste) and
  the two menu actions below. **Properties** is not in the menu, because there is no inspector to
  open.

The node's context menu (right-click on the canvas) has:

- **Quick edit**, from compose-ui-builder#369: the card beside the node for one property. It edits
  the design like any other edit, so autosave writes it with `openai/resources/write` and `ifMatch`.
- **Comment**: a small field beside the node. Enter or **Send** sends it; Shift+Enter is a new
  line; Escape, **Cancel** or a press outside closes it. It takes the keyboard while it is open,
  which the quick editor deliberately does not. An empty comment sends nothing. Sending does two
  things, in this order:
  1. `ui/update-model-context` with the node drawn on its own (an `image` block, PNG, titled
     `_meta["openai/title"] = "<Component> · <nodeId>"`) where the editor could draw it, and a
     text block for the model only (`annotations.audience: ["assistant"]`): the comment, how to act
     on it (edit that node by id in the `.uid`, then render device previews in the chat), and the
     node's detail as JSON. `structuredContent` is `{ comment: { nodeId, text }, selection }`.
  2. One `ui/message`, `role: "user"`, with `_meta["openai/message"] = { target: "active",
     send: true }`: the comment as plain text, then a titled text block for the node (component,
     id, path from the root, properties), which the host shows as one labelled inline item.

  Context first, because a message sent at once starts the turn, and the turn reads the model
  context attached when it starts. The other order would hand the picture to the turn after. The
  comment's context replaces the selection's, and the next selection replaces it in turn.

  **No "also post to the design's comment thread".** The Comments dock's thread is the server's
  (`onPostComment`, over the live session), and this host has no such seam: a `.uid` opened from
  the host's files has no server, so the chat is its discussion. A design homed on a server (R4)
  opened here would need that seam first.

### Device previews belong in the chat

The panel shows the design; it is not where the result of a change should be checked. **After
changing a design, an agent renders its device previews in the chat with `render_preview` or
`render_matrix`** rather than asking the person to look at the editor panel. The comment's
assistant-only block says so, and so does the guide the editor's help opens
(`UI_BUILDER_GETTING_STARTED.md` → *In ChatGPT or Codex desktop*). The same line belongs in
compose-ag-plugin's `harness-notes` for the MCP App tools.

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

- `<base href="__COMPOSE_UI_BUILDER_ASSET_BASE__">` and
  `globalThis.composeUiBuilderMcpApp = { assetBase, layout, devicePresets, version }`. The version
  is written in at build time. The asset base is the placeholder a server **must** fill in: an
  absolute URL, ending in `/`, of the unpacked archive's root. The layout,
  `__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__`, and the device presets,
  `__COMPOSE_UI_BUILDER_DEVICE_PRESETS__`, are the ones it **may** fill in; see below.
- The same import map, preloads, boot screen and module script as `index.html`, all relative, so
  they resolve against the base.
- In this mode the editor's own requests (`sameOriginRequestUrl`) must stay under
  `assetBase`, or under `catalogBase` when it is set.

The archive manifest `ui-builder-web.json` carries `"mcpApp": 1`. That number is the contract
between the shell and the server: the placeholders' names, and the shell's path in the archive.
`verifyUiBuilderWebArchive` checks that the shell is packaged, that both placeholders are intact
and that its version was written in. The layout placeholder did not bump it: a version-1 server
that leaves it alone gets the focused canvas, which is the default anyway.

### The initial layout

`composeUiBuilderMcpApp.layout` chooses the editor the panel opens in:

| Value | Opens |
| --- | --- |
| `focused` | The focused canvas. |
| `full` | The full desktop editor. |
| anything else, including the placeholder left unfilled | The focused canvas. |

The person can switch either way from the file bar afterwards; the value only sets where they start.
A `?layout=focused|full` on the shell's own URL wins over it, for a person or a harness opening the
shell by hand; a server does not need it.

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
     `__COMPOSE_UI_BUILDER_ASSET_BASE__` replaced by the asset base, and every
     `__COMPOSE_UI_BUILDER_MCP_APP_LAYOUT__` replaced by the `uiBuilderMcpAppLayout` setting from
     `~/.compose-preview/settings.json` (compose-preview-server#1242): exactly `focused` or `full`.
     Default `focused` when the setting is absent; pass any other value through as `focused`, or
     validate it in the settings schema as the enum `["focused", "full"]`. The value is substituted
     once, when the resource is read; changing the setting applies to the next open. Every
     `__COMPOSE_UI_BUILDER_DEVICE_PRESETS__` may be replaced by the server's own
     `<origin>/api/ui-builder/v1/device-presets`: with it the Screen dock offers device menus and
     the variant strip draws the design's export devices; left unfilled there are none. A static
     host can serve a copy of that answer instead, as the landing page does (`site/js/mcp-host.js`).
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
It runs the full editor (`layout` filled in as `full`), then the focused canvas (the placeholder
left unfilled), and fails unless:
- the design opens;
- nothing is written until an edit;
- a selection becomes model context;
- an edit saves with `ifMatch`;
- an external edit is picked up;
- the focused canvas opens by default, and **Full editor** switches to the full editor and back;
- a comment sent from the node menu arrives as exactly one `ui/update-model-context` (an image and
  an assistant-only block) followed by exactly one `ui/message` to the active thread, and an empty
  one sends nothing.
