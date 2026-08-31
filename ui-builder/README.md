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

The full Jetcaster fixture still fails closed: two asset nodes use `matchParentSize`, for which this
spike has no renderer-supplied resolved pixel bounds, and Skia also emits anonymous rasterized
filtered icons. This is a
GO for the bounded structured-SVG execution technique, but remains NO-GO for product/Figma export:
production JVM execution belongs behind `:render-host`, there is no server/MCP export integration,
and no SVG has been imported and raster-compared in Figma.

The public operation fixture remains under `docs/design/fixtures/ui-builder`. JVM tests consume that
same file directly; the Wasm application and eventual MCP adapter must call the same reducer API.
