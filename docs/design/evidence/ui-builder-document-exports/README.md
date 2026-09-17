# Real browser and MCP export delivery

The existing `:ui-builder` WASM app was built with locally staged contracts and JSON compiler
publications, then served by this repository's installed server. `editor.png` shows the saved design
with the export menu closed; `menu.png` shows the real menu with JSON and Remote document downloads.
These are Chrome screenshots, not recreated controls.

The sample was created through `POST /api/ui-builder/v1/requests`, using the Remote catalog's actual
capability pin. Its semantic hierarchy is in `sample.document.json`: a Box bound to integer `page`,
with two case children and a fallback. No flat operation nodes were added to the authoring tree.

The browser clicked **Download Remote document (.rc)** and saved `remote-export-proof.rc`. The test
compared those bytes to the normal HTTP export pinned at revision 0. A subsequent hosted MCP
`ui_builder_export` call with `format: rc` and `revision: 0` returned the same 757 bytes and digest.
`verification.json` records the filename, revision, size and SHA-256. The browser also downloaded
1,659 bytes of JSON source; hosted MCP returned identical UTF-8 bytes and digest, recorded in
`json-verification.json`. Local files and temporary test
credentials are not embedded in shared URLs.

This capture proves the export menu and delivery path. It is **not** a claim of live binary-preview
fidelity: the fill-only child collapsed in this capture, while the compiler/player proof exercises
generated documents separately. The subsequent [canvas fix and browser comparison](../ui-builder-canvas-fill/README.md)
repair that layout issue using this same saved document. The subsequent
[live document preview proof](../ui-builder-live-document-preview/README.md) verifies actual exported
bytes, click actions and automatic refresh following a live MCP edit.

Automated coverage is in `RemoteDocumentExportExecutorTest`, `ServeUiBuilderRoutesTest`,
`UiBuilderMcpAdapterTest`, and `EditorExportMenuTest`. The first includes an actual persistent
service, authenticated HTTP requests, source compilation, and byte/digest/revision comparisons for
the download route. The route test verifies unsupported-document diagnostics become HTTP 422.
