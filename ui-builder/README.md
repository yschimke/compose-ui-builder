# UI builder

The [Remote Compose composition design](../docs/design/UI_BUILDER_REMOTE_COMPOSE.md) describes how
nested documents, named slots, state, events, and portable player services fit into the builder.

This module is the extraction boundary for the Compose UI builder. It owns the candidate document
reducer, native Compose renderer and code exporter, but has no dependency on `:server`,
`:render-host` or `:wasm-ui`.

The JVM target also contains the saved-document structured-SVG execution bridge. It records the
same native renderer through Skia's SVG canvas and validates provenance, structure, external
references and declared raster fallbacks before returning output. Vector-only subsets are proven;
the representative saved card now passes with nested text, clipping, card elevation, and one
renderer-correlated embedded raster asset. Its SVGDOM round trip differs from the same-runtime
Compose raster on `2.2533%` of pixels at per-channel tolerance `26/255`, below the spike's `3%`
gate. Correlation is by an isolated render of the exact asset node and embedded-payload SHA-256;
each fallback also carries its rendered pixel size and an explicit `project-owned-artwork/v1`
source identity tied to the encoded PNG hash. These are original deterministic test assets, not
upstream podcast artwork. The same decoded resource is rendered by Compose/Wasm. Anonymous Skia images
and ambiguous duplicate payloads still fail closed.

The full frozen Jetcaster fixture now exports under its checked-in capability catalog. Resolved
`matchParentSize` bounds come from the same Compose layout pass, known catalog icons remain vector
paths, and the four authored images are explicit embedded-raster fallbacks. The recorder correlates
each text fragment to measured Compose bounds and baselines, then writes an escaped authored node
identity, Material typography token, explicit Inter adapter provenance, style and numeric weight.
The deterministic current SVG has 37 editable text fragments: 25 regular and 12 medium. A focused
fixture additionally proves regular, medium and bold serialization and stable bytes.

This is a GO for the bounded structured-SVG execution technique, but remains NO-GO for
product/Figma export. The last completed Figma import used the preceding 139,673-byte SVG: it
retained exact 1280x800 bounds, 37 editable text nodes, 83 vector nodes and all four image paints,
but normalized all text to Inter Regular and differed from its then-current clean Wasm render by
`5.597%` at pixelmatch threshold `0.1`. The current typography-provenance SVG has not been uploaded:
the private draft exists but remains empty while artifact upload awaits explicit authorization.
Figma weight preservation and current raster parity are therefore unmeasured. The versioned
conformance record separates current local evidence from the last completed import in
`docs/design/fixtures/ui-builder/jetcaster-discover-figma-import-v1.json`. Production JVM execution
still belongs behind `:render-host`, and there is no server/MCP export integration.

The public operation fixture remains under `docs/design/fixtures/ui-builder`. JVM tests consume that
same file directly; the Wasm application and eventual MCP adapter must call the same reducer API.

The hosted `/ui-builder/` application opens a catalog-aware New design chooser when no design is
named, then a native Compose editor backed by the persistent live service. New design remains
available in the desktop toolbar and compact More menu. Its searchable catalog, layers tree,
canvas selection overlay and property inspector all mutate the document through
`CollaborationReducer`; catalog Adds resolve against the visibly named selected slot, while drags
resolve against the compatible slot under the pointer. The editor
also exposes an accessible Add action for the same compatible target and preserves selection,
search, inspector mode, and operation numbering when live authoritative snapshots arrive. The editor
measures the design at its pinned 1280×800dp viewport and applies a sibling visual transform to fit
the workspace, so the side panels cannot silently trigger a compact layout. That transform now
*frames* the design rather than stopping at 1:1 — a design smaller than the window is scaled up to
fill it — and a zoom control in the corner of the canvas steps a ladder of fixed scales, pins 100%,
or hands the frame back to the fit. Zooming past the workspace scrolls it. `?mode=interactive-editor-clean`
is the exception and opens pinned at 1:1, because that lane exists to compare the editor's pixels
against the clean harness's. Explicit `?mode=…` URLs retain the frozen local fixtures;
`?mode=jetcaster-builder` remains the clean harness surface and does not compose editor controls or
transforms.

A palette drag carries a live rendering of the component under the pointer and highlights the exact
compatible slot it will fill. Remote Compose rows use their published capture as the drag preview;
their drop captures that slot before fetching the document, then revalidates it when the bytes
arrive. Pointer-resolved slots are validated directly by the reducer; they no longer have to agree
with the layer selected before the drag began. Clicking a layer focuses the editor, and Delete or
Backspace removes the selected subtree through the same guarded delete operation as the context
menu.

On desktop, the renderer control is an additive one-, two-, or three-pane workspace. The Wasm
visual editor is always first. The second pane is the static target render compiled by the host.
The third is a clean interactive Wasm rendition; Remote M3 uses its actual CMP/Wasm player there,
while catalogs that declare Wasm stand-ins say so in the chooser.

A right-click on a layer — in the tree or on the design, which is hit-tested against the boxes the
renderer already reports — selects it and offers the verbs that act on it: properties, duplicate,
copy, cut, paste, delete, wrap and unwrap. Those verbs left the bar above the canvas, which now
carries the selection's name, the way to its properties, and one overflow holding the same menu.

On viewports narrower than 840dp, the interactive editor starts with only the scaled design and a
compact toolbar visible. Persistent bottom tabs open collapsible component and property docks;
desktop widths retain the three-column workspace.

The property inspector opens on what the node actually carries — the properties the Compose export
would write, plus anything required, bound or in error — rather than every declaration the catalog
allows on that component. A search box filters those and reaches the rest: a property the node does
not have yet is added from the same field, which reveals its control without writing anything to the
document until the control is used.

The inspector's top-level Theme mode edits the design-wide Material colour scheme, type scale, and
shape radius. Theme metadata is stored as capability-declared properties on the root Material
surface and submitted as one collaboration batch, so a theme application is persistent, shared,
and one-step undoable without introducing a server-only mutation outside the released protocol.

`?storage=local` on a design URL opens a design this browser holds instead of one the server holds.
The editor above it is unchanged: the same protocol client reaches a `LocalUiBuilderService` in the
page, which applies the same `CollaborationReducer` and persists a seed document plus its accepted
command log to `localStorage` — so undo survives a reload, and the editing loop keeps working with
the server unreachable. Export, native render, comments and reference pictures stay server-side and
say so. The mode, the catalog fallback it depends on, the compaction rule and what "mostly offline"
does and does not cover are in
[`docs/design/UI_BUILDER_LOCAL_STORAGE.md`](../docs/design/UI_BUILDER_LOCAL_STORAGE.md).

A **History** rail item opens two things at once, because they are the same history asked two
questions: the panel listing what has been done, and a strip of revision thumbnails under the
canvas showing what each of it looked like. The pictures are rebuilt from the compensating changes
the reducer already records and drawn through the renderer drawing the canvas — nothing is stored
per revision, and a revision the record cannot reach keeps its words and loses its picture rather
than being approximated. Clicking a row looks at that revision read-only, replacing the editing
canvas rather than covering it; picking a second row compares the two, side by side, with the
differences read off the two documents. Why that is a separate surface from the viewer's
published-render versions, and where the two meet, is in
[`docs/design/UI_BUILDER_REVISION_HISTORY.md`](../docs/design/UI_BUILDER_REVISION_HISTORY.md).
