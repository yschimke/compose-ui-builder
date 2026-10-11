package ee.schimke.composeai.uibuilder.export

/**
 * The bytes behind a design's asset key and their media type, for the lane that ships pictures as
 * files (`<key>.<extension>`) beside the source (`docs/design/UI_BUILDER_EXPORT_BUNDLE.md`).
 *
 * Caller-owned like [WidgetAssetBytes]: only a host with the store can answer. `null` makes the
 * export refuse by name rather than reference a file the archive lacks.
 */
fun interface WidgetAssetContents {
  /** The content for [assetKey], or `null` when this registry cannot supply it. */
  fun contents(assetKey: String): WidgetAssetContent?
}

/**
 * @property mediaType the binding's own `mediaType`, which names the file's extension.
 * @property base64 the picture's bytes, encoded as [WidgetAssetBytes] answers.
 */
data class WidgetAssetContent(val mediaType: String, val base64: String)
