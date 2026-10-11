package ee.schimke.composeai.uibuilder.export

/**
 * A widget design as a project fragment: the source, instructions, and its pictures as files. Not
 * an archive: this module does no I/O, and hosts frame the same entries into a zip.
 *
 * @property sourceFileName what to call [source] in the archive; the class it declares, plus `.kt`.
 * @property readme the archive's `README.md` — where the files go and what the application passes.
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
 * @property path relative to the archive root (`assets/uibuilder/<design>/<key>.png`, i.e.
 *   `src/main/assets/…` once unpacked).
 * @property base64 the bytes, still encoded; the host framing the archive decodes them.
 */
data class WidgetBundleFile(val path: String, val mediaType: String, val base64: String)
