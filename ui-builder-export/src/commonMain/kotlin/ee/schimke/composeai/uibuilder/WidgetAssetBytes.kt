package ee.schimke.composeai.uibuilder

/**
 * The bytes behind a design's asset key, base64-encoded, for the lane that **inlines** them.
 *
 * A Wear widget background is a `RemoteImageBitmap` inside the `WearWidgetDocument`, and the
 * document is built in the application's process by `provideWidgetData` before the launcher ever
 * sees it. What that rules out is a name the **drawing** side resolves — the host draws the
 * document with neither the app's process nor its resources, so an `R.drawable` in the brush chain
 * is not there to resolve. It does not rule out loading: an app can read the picture in
 * `provideWidgetData`, where it still has a `Context`, and pass the pixels in.
 *
 * So inlining is this lane's answer rather than the only one. Generated source that must stand
 * alone — no archive to unpack, no source set to drop files into — has nowhere to put a picture
 * except in itself, which is why this seam returns encoded bytes rather than an identifier. The
 * export that ships the bytes beside the source instead resolves a path, and is
 * `docs/design/UI_BUILDER_EXPORT_BUNDLE.md` (yschimke/compose-preview-server#528).
 *
 * Caller-owned on purpose. A design's `assets` map holds a media type, a digest and a storage key,
 * never the bytes, so the registry that can answer this lives above the exporter — the server's
 * asset store, or the editor's own copy in the browser. An implementation that knows nothing
 * answers `null`, and the export refuses by name rather than emitting a picture it does not have.
 */
fun interface WidgetAssetBytes {
  /** Base64 for [assetKey], or `null` when this registry cannot supply it. */
  fun base64(assetKey: String): String?
}
