package ee.schimke.composeai.uibuilder

/**
 * The bytes behind a design's asset key, base64-encoded, for the lane that has to **inline** them.
 *
 * A Wear widget is drawn by the system host: the launcher renders the `WearWidgetDocument` out of
 * the application's process, with neither its resources nor its assets. So a background picture
 * cannot be a name the drawing side resolves — no `R.drawable`, no asset path — and the pixels have
 * to travel inside the document. Generated source carries them, which is why this seam returns the
 * encoded bytes rather than an identifier.
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
