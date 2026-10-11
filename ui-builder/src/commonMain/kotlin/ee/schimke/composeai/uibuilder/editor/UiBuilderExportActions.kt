package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures

/**
 * Getting a design out of the builder, with the catalog viewer's verbs, gated by what the pinned
 * catalog can render.
 *
 * - **Copy SVG** puts SVG markup on the clipboard: the Figma route, pasted as editable layers.
 * - **Copy PNG** puts the picture on the clipboard as an image.
 * - **Copy link** copies a live URL that renders the current committed revision on every request.
 * - **Download** saves the file through the browser.
 *
 * [UiBuilderExportHost] is the seam to the clipboard, network, downloads and origin: the editor
 * decides what to offer, the host does it and answers with a sentence. Null in previews and tests,
 * where the menu is absent.
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

  /**
   * An A2UI design's messages (`createSurface`, `updateDataModel`, `updateComponents`) as streamed
   * JSON Lines, via the same `json` route as [Json].
   */
  A2uiJson("A2UI JSON", "json"),
}

/** What the host does with a design's picture; see [EditorExportFormat]. */
interface UiBuilderExportHost {
  /** The formats the pinned catalog can render, in menu order. Empty hides the menu. */
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

  /**
   * Whether this host can hand a file to the platform share sheet (mobile browsers whose
   * `navigator.canShare` accepts files).
   */
  val supportsShare: Boolean
    get() = false

  /**
   * Hands the rendered design to the share sheet and returns a toolbar sentence. Falls back to
   * saving the file.
   */
  suspend fun share(format: EditorExportFormat): String = download(format)
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
          EditorExportFormat.A2uiJson -> "The messages an agent sends to draw this surface"
        }
  }

  data class CopyLink(override val format: EditorExportFormat) : EditorExportMenuEntry {
    override val label: String
      get() = "Copy ${format.label} link"

    override val detail: String
      get() = "A live URL that always shows the current design"
  }

  data class Share(override val format: EditorExportFormat) : EditorExportMenuEntry {
    override val label: String
      get() = "Share ${format.label}"

    override val detail: String
      get() = "Send the file to another app"
  }

  data class Download(override val format: EditorExportFormat) : EditorExportMenuEntry {
    override val label: String
      get() = "Download ${format.label}"

    override val detail: String
      get() = "Save the current design as a file"
  }
}

/**
 * The Export menu, grouped by verb (every Copy, then Copy link, then Download) since people pick
 * the action before the format. Catalog order within a group, SVG first where available.
 */
fun exportMenuEntries(
  formats: List<EditorExportFormat>,
  supportsLinks: Boolean = true,
  supportsShare: Boolean = false,
): List<List<EditorExportMenuEntry>> =
  if (formats.isEmpty()) emptyList()
  else
    listOfNotNull(
      formats.filter { it != EditorExportFormat.Rc }.map(EditorExportMenuEntry::CopyPicture),
      if (supportsLinks) formats.map(EditorExportMenuEntry::CopyLink) else emptyList(),
      // Only the two pictures: a phone's share sheet has somewhere to send an SVG or a PNG, and
      // nowhere a Remote Compose document means anything.
      formats
        .filter { it == EditorExportFormat.Svg || it == EditorExportFormat.Png }
        .map(EditorExportMenuEntry::Share)
        .takeIf { supportsShare && it.isNotEmpty() },
      formats.map(EditorExportMenuEntry::Download),
    )

/** Runs one row against the host and hands back the sentence it answered with. */
suspend fun UiBuilderExportHost.perform(entry: EditorExportMenuEntry): String =
  when (entry) {
    is EditorExportMenuEntry.CopyPicture -> copyPicture(entry.format)
    is EditorExportMenuEntry.CopyLink -> copyLink(entry.format)
    is EditorExportMenuEntry.Share -> share(entry.format)
    is EditorExportMenuEntry.Download -> download(entry.format)
  }

/**
 * The formats a catalog's export capabilities amount to, SVG first. Two booleans rather than
 * `ExportCapabilitiesV1` to avoid a contracts dependency.
 */
fun exportFormatsFor(
  svg: Boolean,
  png: Boolean,
  json: Boolean = false,
  rc: Boolean = false,
  a2uiJson: Boolean = false,
): List<EditorExportFormat> = buildList {
  if (svg) add(EditorExportFormat.Svg)
  if (png) add(EditorExportFormat.Png)
  if (UiBuilderBuildFeatures.remoteCompose && json) add(EditorExportFormat.Json)
  if (UiBuilderBuildFeatures.remoteCompose && rc) add(EditorExportFormat.Rc)
  // Not behind the Remote Compose flag: an A2UI design's JSON is its A2UI messages.
  if (a2uiJson) add(EditorExportFormat.A2uiJson)
}

/**
 * This host for a caller the server will not export for (no write access): the same menu, every row
 * answering with [reason], so the reader is not left guessing why there is no Export.
 */
fun UiBuilderExportHost.refusing(reason: String): UiBuilderExportHost {
  val delegate = this
  return object : UiBuilderExportHost {
    override val formats: List<EditorExportFormat>
      get() = delegate.formats

    override val supportsLinks: Boolean
      get() = delegate.supportsLinks

    override suspend fun copyPicture(format: EditorExportFormat): String = reason

    override suspend fun copyLink(format: EditorExportFormat): String = reason

    override suspend fun download(format: EditorExportFormat): String = reason

    override val supportsShare: Boolean
      get() = delegate.supportsShare

    override suspend fun share(format: EditorExportFormat): String = reason
  }
}
