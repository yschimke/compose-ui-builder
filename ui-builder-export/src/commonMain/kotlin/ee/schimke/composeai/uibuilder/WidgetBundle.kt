package ee.schimke.composeai.uibuilder

/**
 * A widget design as a project fragment: the source, what to do with it, and its pictures as files.
 *
 * **Not an archive.** This module is multiplatform and does no I/O; framing these entries into a
 * zip is the host's job — the server writing a download, or the editor writing one in the browser —
 * and both write the same entries. Keeping the framing out here is also what keeps the two from
 * disagreeing about what is *in* a bundle, which is the drift `:ui-builder-export` exists to end.
 *
 * @property sourceFileName what to call [source] in the archive; the class it declares, plus `.kt`.
 * @property readme the archive's `README.md` — where the files go and what the application passes.
 *   Written here rather than by the host because the paths it names are the ones the source opens.
 * @property files the pictures, keyed by the path they take inside the archive.
 */
data class WidgetBundle(
  val sourceFileName: String,
  val source: String,
  val readme: String,
  val files: List<WidgetBundleFile>,
)

/**
 * One picture in a bundle.
 *
 * @property path where it goes, relative to the archive root —
 *   `assets/uibuilder/<design>/<key>.png` and its siblings, which is `src/main/assets/…` in the
 *   module it is unpacked into.
 * @property base64 the bytes, still encoded: this module never decodes them, and the host that
 *   frames the archive is the one that writes them.
 */
data class WidgetBundleFile(val path: String, val mediaType: String, val base64: String)
