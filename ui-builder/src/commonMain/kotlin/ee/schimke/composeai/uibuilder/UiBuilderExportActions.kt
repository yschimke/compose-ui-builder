package ee.schimke.composeai.uibuilder

/**
 * Getting a design *out* of the builder, in the words the catalog viewer already uses.
 *
 * ## Why this exists
 *
 * The server has exported a design as PNG and as Figma-compatible SVG since the export lane landed,
 * and the editor offered neither: the only way to hold the picture was a protocol request or an MCP
 * call, and the only thing the editor itself did with the PNG export was attach it back as a
 * reference. A designer who wanted the screen in Figma had nothing to press.
 *
 * The catalog viewer's preview pages answer the same question with one row — **Copy link**, **Copy
 * PNG** / **Copy SVG**, **Download** — and a person who has used one should not have to learn a
 * second vocabulary in the other. So the builder's Export menu carries the same three verbs per
 * format, gated by what the pinned catalog says it can render.
 *
 * ## What each verb means
 *
 * - **Copy SVG** puts the SVG markup on the clipboard as text. That is the Figma route: Figma
 *   pastes SVG markup as editable layers, which is what the structured export is for.
 * - **Copy PNG** puts the picture on the clipboard as an image, for Figma, Slack, a document.
 * - **Copy link** copies a URL that renders the design *as it stands*. The address is live: the
 *   server draws the current committed revision on every request, so a link pasted into a pull
 *   request or a README keeps up with the design rather than freezing the moment it was copied.
 * - **Download** saves the file through the browser.
 *
 * ## What is host-owned
 *
 * Every one of these reaches something `commonMain` cannot: the clipboard, the network, the
 * browser's download path, the page's own origin. [UiBuilderExportHost] is the seam. The editor
 * decides what to offer and in which order; the host does it and answers with a sentence the
 * toolbar shows. Null in every preview and test, where the menu then offers nothing rather than an
 * action that cannot work — the same rule the reference importer follows.
 */
enum class EditorExportFormat(
  /** How the menu names it. */
  val label: String,
  /** The file extension the download carries and the live route ends in. */
  val extension: String,
) {
  Svg("SVG", "svg"),
  Png("PNG", "png"),
  Json("JSON", "json"),
  Rc("Remote document (.rc)", "rc"),
}

/** What the host does with a design's picture; see [EditorExportFormat]. */
interface UiBuilderExportHost {
  /**
   * The formats the pinned catalog can render, in the order the menu lists them.
   *
   * Empty hides the menu: a catalog whose renderer cannot draw a design has nothing to export, and
   * an Export button that always refuses is worse than none.
   */
  val formats: List<EditorExportFormat>

  /** A local document has no shareable server export URL. */
  val supportsLinks: Boolean
    get() = true

  /** Copies the rendered design to the clipboard; returns a sentence for the toolbar. */
  suspend fun copyPicture(format: EditorExportFormat): String

  /** Copies the live URL of the rendered design; returns a sentence for the toolbar. */
  suspend fun copyLink(format: EditorExportFormat): String

  /** Saves the rendered design through the browser; returns a sentence for the toolbar. */
  suspend fun download(format: EditorExportFormat): String
}

/** One row of the Export menu. */
sealed interface EditorExportMenuEntry {
  val format: EditorExportFormat
  val label: String
  /** The second line: what the row is *for*, since the verb alone does not say. */
  val detail: String

  data class CopyPicture(override val format: EditorExportFormat) : EditorExportMenuEntry {
    override val label: String
      get() = "Copy ${format.label}"

    override val detail: String
      get() =
        when (format) {
          EditorExportFormat.Svg -> "Paste into Figma as editable layers"
          EditorExportFormat.Png -> "Paste anywhere as a picture"
          EditorExportFormat.Json -> "Editable Remote Compose source"
          EditorExportFormat.Rc -> "Use Download to save the binary document"
        }
  }

  data class CopyLink(override val format: EditorExportFormat) : EditorExportMenuEntry {
    override val label: String
      get() = "Copy ${format.label} link"

    override val detail: String
      get() = "A live URL that always shows the current design"
  }

  data class Download(override val format: EditorExportFormat) : EditorExportMenuEntry {
    override val label: String
      get() = "Download ${format.label}"

    override val detail: String
      get() = "Save the current design as a file"
  }
}

/**
 * The Export menu, verb by verb.
 *
 * Grouped by what the row does rather than by format — every Copy, then every Copy link, then every
 * Download — because a person opening this menu knows which *action* they want (paste into Figma,
 * share a link, keep a file) before they know which format; a menu grouped by format would make
 * them read every row to find the verb. Within a group the catalog's order stands, and the catalog
 * lists SVG first where it has it, because the Figma route is the one this menu exists for.
 */
fun exportMenuEntries(
  formats: List<EditorExportFormat>,
  supportsLinks: Boolean = true,
): List<List<EditorExportMenuEntry>> =
  if (formats.isEmpty()) emptyList()
  else
    listOf(
      formats.filter { it != EditorExportFormat.Rc }.map(EditorExportMenuEntry::CopyPicture),
      if (supportsLinks) formats.map(EditorExportMenuEntry::CopyLink) else emptyList(),
      formats.map(EditorExportMenuEntry::Download),
    )

/** Runs one row against the host and hands back the sentence it answered with. */
suspend fun UiBuilderExportHost.perform(entry: EditorExportMenuEntry): String =
  when (entry) {
    is EditorExportMenuEntry.CopyPicture -> copyPicture(entry.format)
    is EditorExportMenuEntry.CopyLink -> copyLink(entry.format)
    is EditorExportMenuEntry.Download -> download(entry.format)
  }

/**
 * The formats a catalog's export capabilities amount to, SVG first.
 *
 * Kept as two booleans rather than the wire type, because `ExportCapabilitiesV1` is published from
 * compose-preview-contracts and `commonMain` has no reason to depend on it for two flags. SVG leads
 * for the reason [exportMenuEntries] gives.
 */
fun exportFormatsFor(
  svg: Boolean,
  png: Boolean,
  json: Boolean = false,
  rc: Boolean = false,
): List<EditorExportFormat> = buildList {
  if (svg) add(EditorExportFormat.Svg)
  if (png) add(EditorExportFormat.Png)
  if (UiBuilderBuildFeatures.remoteCompose && json) add(EditorExportFormat.Json)
  if (UiBuilderBuildFeatures.remoteCompose && rc) add(EditorExportFormat.Rc)
}
