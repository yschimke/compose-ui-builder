package ee.schimke.composeai.uibuilder.export

/**
 * The bytes behind a design's asset key, base64-encoded, for the lane that inlines them.
 *
 * The widget host draws the document without the app's resources, so pixels must be in the
 * document; standalone source has nowhere else to put them. (The bundle export ships files instead:
 * `docs/design/UI_BUILDER_EXPORT_BUNDLE.md`.)
 *
 * Caller-owned: a design's `assets` hold metadata, not bytes, so only the host's store can answer.
 * `null` makes the export refuse by name.
 */
fun interface WidgetAssetBytes {
  /** Base64 for [assetKey], or `null` when this registry cannot supply it. */
  fun base64(assetKey: String): String?
}
