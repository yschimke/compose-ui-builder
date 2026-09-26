# A2UI palette thumbnails

`CatalogThumbnailRenderTest`'s contact sheet for `a2ui-catalog`
(`ui-builder/build/catalog-thumbnails/a2ui-catalog.png`), before and after the thumbnail frame
started using the catalog's own list container.

- `before.png`: every tile shows the generic handle. The frame was a `layout/box` with a `size`
  modifier, and A2UI has neither, so every insert was refused.
- `after.png`: every tile draws its component, framed by `a2ui/Column`. The Wasm canvas draws A2UI
  components as labelled placeholders, so that is what the tiles show.

Regenerate with `./gradlew :ui-builder:jvmTest --tests '*CatalogThumbnailRenderTest*'`.
