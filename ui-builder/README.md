# UI builder

This module is the extraction boundary for the Compose UI builder. It owns the candidate document
reducer, native Compose renderer and code exporter, but has no dependency on `:server`,
`:render-host` or `:wasm-ui`.

The JVM target also contains the saved-document structured-SVG execution bridge. It records the
same native renderer through Skia's SVG canvas and validates provenance, structure, external
references and declared raster fallbacks before returning output. Vector-only subsets are proven;
the full Jetcaster fixture currently fails closed on Skia-rasterized filtered icons and is not yet
claimed as Figma-compatible. This remains a feasibility spike: production JVM execution belongs
behind `:render-host`, and there is no server/MCP export integration yet.

The public operation fixture remains under `docs/design/fixtures/ui-builder`. JVM tests consume that
same file directly; the Wasm application and eventual MCP adapter must call the same reducer API.
