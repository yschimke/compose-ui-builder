package ee.schimke.composeai.uibuilder

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One unstored axis the variant strip draws the design on.
 *
 * Devices are deliberately not among these: they live in the document, as
 * `DesignEnvironmentV1.exportDevices`, because the Compose export already writes them as
 * `@Preview(device = …)` and the point of the strip is that the set you look at is the set that
 * ships. These three have no stored home — the environment contract is published from
 * `compose-preview-contracts` and closed to this repository — and are honestly what they are: a way
 * of looking, off again when the design is reopened
 * ([`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md)).
 */
enum class EditorVariantAxis(val label: String) {
  /** The design under the dark scheme, whatever its own theme says. */
  Dark("Dark"),

  /** The design mirrored, for the half of the world that reads the other way. */
  Rtl("RTL"),

  /** The design at the largest font scale the accessibility settings commonly reach. */
  LargeFont("Font 1.5×"),
}

/** The font scale [EditorVariantAxis.LargeFont] draws at. */
const val LARGE_FONT_VARIANT_SCALE: Double = 1.5

/**
 * One pane of the variant strip: a title, and the document to draw in it.
 *
 * A whole document rather than an environment delta because that is what [UiBuilderSurface] reads —
 * theme, density, font scale and layout direction all come off `document.environment` — so a
 * variant is drawn by the ordinary renderer with no variant-shaped parameter threaded through it.
 */
data class UiBuilderVariantPane(
  /** Stable across recompositions and unique in the strip; also the surface's render session id. */
  val id: String,
  /** What the pane is called on its label, e.g. `Pixel 7` or `Dark`. */
  val label: String,
  /** The frame this pane is drawn at, in the design's own dp. */
  val widthDp: Float,
  val heightDp: Float,
  val document: UiBuilderDocument,
)

/**
 * The panes to draw beside the editing pane, in the order they are shown, or empty for none.
 *
 * Devices first and then the unstored axes, because the devices are the design's own claim and the
 * axes are a question being asked of it. A device the host does not offer a preset for is skipped
 * rather than guessed at: a preset carries the only geometry there is (see
 * [UiBuilderDevicePreset]), so inventing a frame for an unknown id would draw a picture no renderer
 * would reproduce.
 *
 * Every pane is read-only where it is drawn; nothing here decides that, but nothing here makes any
 * sense unless it holds — see the module doc for why one editing pane is the load-bearing rule.
 */
fun UiBuilderDocument.variantPanes(
  presets: List<UiBuilderDevicePreset>,
  axes: Set<EditorVariantAxis>,
): List<UiBuilderVariantPane> {
  val settings = screenEnvironmentSettings()
  val devicePanes =
    // Distinct, because `exportDevices` is stored verbatim: `SetExportDevicesEnvironmentChangeV1`
    // imposes no uniqueness, so a document written through the protocol or MCP can name one device
    // a thousand times — and each repeat would be another full `UiBuilderSurface`, all sharing one
    // pane id. The picker only ever writes a set; this is about what a document can *hold*.
    settings.exportDevices.distinct().mapNotNull { id ->
      val preset = presets.firstOrNull { it.id == id } ?: return@mapNotNull null
      UiBuilderVariantPane(
        id = "variant-device-${preset.id}",
        label = preset.label,
        widthDp = preset.widthDp.toFloat(),
        heightDp = preset.heightDp.toFloat(),
        document =
          withEnvironmentOverrides(
            mapOf(
              "widthDp" to JsonPrimitive(preset.widthDp),
              "heightDp" to JsonPrimitive(preset.heightDp),
              "density" to JsonPrimitive(preset.density),
            )
          ),
      )
    }
  // In the enum's own order rather than in the order they were switched on, so a strip does not
  // reshuffle itself under someone who is comparing two panes in it.
  val axisPanes =
    EditorVariantAxis.entries
      .filter { it in axes }
      .map { axis ->
        UiBuilderVariantPane(
          id = "variant-axis-${axis.name.lowercase()}",
          label = axis.label,
          widthDp = settings.widthDp.toFloat(),
          heightDp = settings.heightDp.toFloat(),
          document = withEnvironmentOverrides(axis.overrides()),
        )
      }
  return devicePanes + axisPanes
}

/** What one axis writes over the design's own environment. */
private fun EditorVariantAxis.overrides(): Map<String, JsonPrimitive> =
  when (this) {
    EditorVariantAxis.Dark -> mapOf("theme" to JsonPrimitive(EditorScreenTheme.Dark.wireValue))
    EditorVariantAxis.Rtl ->
      mapOf("layoutDirection" to JsonPrimitive(EditorLayoutDirection.Rtl.wireValue))
    EditorVariantAxis.LargeFont -> mapOf("fontScale" to JsonPrimitive(LARGE_FONT_VARIANT_SCALE))
  }

/**
 * This document as a variant reads it: the same tree, the same id, the same revision, one or more
 * environment fields written over.
 *
 * The id and the revision are deliberately untouched. [UiBuilderSurface] keys its remembered bounds
 * on `(document.id, document.revision, renderSessionId)`, so the session id is what separates two
 * panes of the same design — giving a variant a document id of its own would instead make every
 * edit look like a different design arriving, and throw away the composition on every keystroke.
 *
 * Numbers are written as strings, matching how the environment is already read back: every reader
 * goes through `jsonPrimitive.contentOrNull` and parses, so a bare number and its text are the same
 * value to all of them, and the text is what the editor's own environment writes.
 */
internal fun UiBuilderDocument.withEnvironmentOverrides(
  overrides: Map<String, JsonPrimitive>
): UiBuilderDocument =
  copy(
    environment =
      JsonObject(environment + overrides.mapValues { (_, value) -> JsonPrimitive(value.content) })
  )
