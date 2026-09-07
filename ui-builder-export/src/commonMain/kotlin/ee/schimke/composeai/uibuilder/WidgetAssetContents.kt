package ee.schimke.composeai.uibuilder

/**
 * The bytes behind a design's asset key **and what kind of picture they are**, for the lane that
 * ships them as files beside the source.
 *
 * The second half is the whole difference from [WidgetAssetBytes]. An inlined picture needs only
 * its bytes — they become a base64 literal, and nothing in the file is named after them. A bundled
 * one becomes a file, and a file has a name: `<key>.<extension>`, where the extension is read from
 * the media type. So this seam answers both, and the exporter owns the path it builds out of them
 * (`docs/design/UI_BUILDER_EXPORT_BUNDLE.md`, yschimke/compose-preview-server#528).
 *
 * Caller-owned for the same reason [WidgetAssetBytes] is: a design's `assets` map holds a media
 * type, a digest and a storage key, never the bytes, so only a host with the store behind it can
 * answer. A registry that cannot answer returns `null`, and the export refuses by name rather than
 * writing source that opens a file the archive does not carry.
 */
fun interface WidgetAssetContents {
  /** The content for [assetKey], or `null` when this registry cannot supply it. */
  fun contents(assetKey: String): WidgetAssetContent?
}

/**
 * @property mediaType the binding's own `mediaType`, which names the file's extension.
 * @property base64 the picture's bytes, encoded — the same encoding [WidgetAssetBytes] answers in,
 *   so a host that can serve one lane can serve the other without decoding first.
 */
data class WidgetAssetContent(val mediaType: String, val base64: String)
