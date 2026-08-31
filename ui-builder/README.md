# UI builder

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
each fallback also carries its pixel size and an explicit `generated-placeholder/v1` source recipe
digest. These are deterministic test assets, not the real catalog artwork. Anonymous Skia images
and ambiguous duplicate payloads still fail closed.

The full frozen Jetcaster fixture now exports under its checked-in capability catalog. Resolved
`matchParentSize` bounds come from the same Compose layout pass, known catalog icons remain vector
paths, and the four authored images are explicit embedded-raster fallbacks. This is a GO for the
bounded structured-SVG execution technique, but remains NO-GO for product/Figma export. The exact
139,673-byte SVG was imported into Figma at 1280x800: it retained 37 editable text nodes, 83 vector
nodes, and all four image paints, but its raster differs from the clean Wasm render by `5.597%` at
pixelmatch threshold `0.1`. The generated fallback artwork does not match the richer Compose canvas
artwork, and Figma text rasterization also differs. The reproducible conformance record is
`docs/design/fixtures/ui-builder/jetcaster-discover-figma-import-v1.json`. Production JVM execution
still belongs behind `:render-host`, and there is no server/MCP export integration.

The public operation fixture remains under `docs/design/fixtures/ui-builder`. JVM tests consume that
same file directly; the Wasm application and eventual MCP adapter must call the same reducer API.

The standalone `/ui-builder/` application now opens a native Compose editor seeded from the frozen
Jetcaster operations fixture. Its searchable M3 catalog, layers tree, canvas selection overlay and
property inspector all mutate the document through `CollaborationReducer`; catalog drops resolve
against the visibly named selected slot. The editor measures the design at its pinned 1280×800dp
viewport and applies a sibling visual transform to fit the workspace, so the side panels cannot
silently trigger a compact Jetcaster layout. `?mode=jetcaster-builder` remains the clean harness
surface and does not compose editor controls or transforms.
