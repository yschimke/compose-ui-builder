package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.LauncherWidgetGrid
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.isLauncherWidget
import ee.schimke.composeai.uibuilder.export.isWearScreen
import ee.schimke.composeai.uibuilder.export.isWearWidget
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

  /**
   * The design at the largest font scale its platform's accessibility settings commonly reach:
   * [LARGE_FONT_VARIANT_SCALE], or [WEAR_LARGE_FONT_VARIANT_SCALE] on a watch. [label] is the
   * phone's; [labelFor] says which one a given design gets.
   */
  LargeFont("Font 1.5×"),
}

/** The font scale [EditorVariantAxis.LargeFont] draws a phone, tablet or desktop design at. */
const val LARGE_FONT_VARIANT_SCALE: Double = 1.5

/**
 * The font scale [EditorVariantAxis.LargeFont] draws a Wear design at: the largest step of Wear
 * OS's own font size setting. A watch never reaches 1.5, so comparing one there would show a layout
 * no wearer can produce while hiding the one the largest setting actually does.
 */
const val WEAR_LARGE_FONT_VARIANT_SCALE: Double = 1.24

/** The scale [EditorVariantAxis.LargeFont] draws this design at; see the two constants. */
fun UiBuilderDocument.largeFontVariantScale(): Double =
  if (isWearScreen() || isWearWidget()) WEAR_LARGE_FONT_VARIANT_SCALE else LARGE_FONT_VARIANT_SCALE

/** What this axis is called for [document]: the large-font one names the scale it draws at. */
fun EditorVariantAxis.labelFor(document: UiBuilderDocument): String =
  when (this) {
    EditorVariantAxis.LargeFont -> "Font ${trimmedDensity(document.largeFontVariantScale())}×"
    else -> label
  }

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
  /** A widget host frame this pane supplies around [document], or the ambient host frame. */
  val wearWidgetHostShape: WearWidgetHostShape? = null,
  /**
   * A launcher widget drawn on a patch of home screen and resized across it a cell at a time,
   * rather than at one fixed size: see [launcherWidgetPreviewPanes]. [widthDp] and [heightDp] are
   * then the whole patch, [LAUNCHER_RESIZE_MAX], so the strip leaves room for the largest size the
   * pane reaches and does not reflow around it as it grows.
   */
  val launcherGridResizable: Boolean = false,
)

/**
 * The preview panes a widget design is drawn in instead of the device and axis strip: a Wear
 * widget's host shapes, or a launcher widget's grid sizes. Null for any other design.
 *
 * [resizable] adds the launcher's animated pane; a host that draws each pane from a fixed render
 * rather than a live composition leaves it out, since a pane that changes size every second would
 * be a render request every second.
 */
internal fun UiBuilderDocument.widgetPreviewPanes(
  resizable: Boolean = true
): List<UiBuilderVariantPane>? =
  wearWidgetScaffoldSize()?.let(::wearWidgetPreviewPanes)
    ?: if (isLauncherWidget()) launcherWidgetPreviewPanes(resizable) else null

/**
 * The fixed grid sizes a launcher widget is previewed at, the sizes a user most often gives a
 * widget on a phone's home screen: the narrow strip, the square, and the two wide ones. The
 * design's own size is added when it is none of these, so the size being authored is always on
 * screen.
 */
internal val LAUNCHER_PREVIEW_SIZES: List<LauncherWidgetGrid.Size> =
  listOf(
    LauncherWidgetGrid.Size(2, 1),
    LauncherWidgetGrid.Size(2, 2),
    LauncherWidgetGrid.Size(3, 2),
    LauncherWidgetGrid.Size(4, 2),
  )

/** The largest footprint the resizable pane reaches, and so the patch of home screen it draws. */
internal val LAUNCHER_RESIZE_MAX: LauncherWidgetGrid.Size = LauncherWidgetGrid.Size(5, 3)

/** The id of the resizable launcher pane; one per strip. */
internal const val LAUNCHER_RESIZABLE_PANE_ID = "preview-launcher-resizable"

/**
 * A launcher widget's panes: first the one that resizes across the grid (when [resizable]), then
 * one per [LAUNCHER_PREVIEW_SIZES] entry, smallest first.
 *
 * Each fixed pane is the same document at that size's reference dp, which is what a launcher hands
 * `Content` when the user drags the widget to that many cells, and the label says both: the cell
 * count is what the user picked, the dp is what the design lays out in.
 */
internal fun UiBuilderDocument.launcherWidgetPreviewPanes(
  resizable: Boolean = true
): List<UiBuilderVariantPane> {
  val own = launcherGridSize()
  val sizes =
    (LAUNCHER_PREVIEW_SIZES + listOfNotNull(own))
      .distinct()
      .sortedWith(compareBy({ it.columns * it.rows }, { it.columns }))
  val fixed = sizes.map { size ->
    UiBuilderVariantPane(
      id = "$LAUNCHER_PANE_ID_PREFIX${size.label}",
      label = launcherSizeLabel(size) + if (size == own) " · Current" else "",
      widthDp = size.widthDp.toFloat(),
      heightDp = size.heightDp.toFloat(),
      document = atLauncherSize(size),
    )
  }
  if (!resizable) return fixed
  val patch = launcherGridPatchDp(LAUNCHER_RESIZE_MAX)
  return listOf(
    UiBuilderVariantPane(
      id = LAUNCHER_RESIZABLE_PANE_ID,
      label = "Resizable",
      widthDp = patch.first,
      heightDp = patch.second,
      document = this,
      launcherGridResizable = true,
    )
  ) + fixed
}

/** The cell count this design is sized at, or null when its frame is not a grid size. */
internal fun UiBuilderDocument.launcherGridSize(): LauncherWidgetGrid.Size? {
  val settings = screenEnvironmentSettings()
  return LauncherWidgetGrid.of(settings.widthDp, settings.heightDp)
}

/** This design at [size]'s reference dp, for a pane to draw. */
internal fun UiBuilderDocument.atLauncherSize(size: LauncherWidgetGrid.Size): UiBuilderDocument =
  withEnvironmentOverrides(
    mapOf("widthDp" to JsonPrimitive(size.widthDp), "heightDp" to JsonPrimitive(size.heightDp))
  )

/** `3x2 · 203×220dp`. */
internal fun launcherSizeLabel(size: LauncherWidgetGrid.Size): String =
  "${size.label} · ${size.widthDp}×${size.heightDp}dp"

/**
 * The patch of home screen [size] cells cover, in dp: whole cells, margins included, which is the
 * room a widget of that size sits in. Its own dp ([LauncherWidgetGrid.Size.widthDp]) is this less
 * one [LauncherWidgetGrid.CELL_MARGIN_DP], half of it on each side.
 */
internal fun launcherGridPatchDp(size: LauncherWidgetGrid.Size): Pair<Float, Float> =
  (LauncherWidgetGrid.CELL_WIDTH_DP * size.columns).toFloat() to
    (LauncherWidgetGrid.CELL_HEIGHT_DP * size.rows).toFloat()

/** [size] held inside 1x1 … [max]. */
internal fun clampLauncherSize(
  size: LauncherWidgetGrid.Size,
  max: LauncherWidgetGrid.Size = LAUNCHER_RESIZE_MAX,
): LauncherWidgetGrid.Size =
  LauncherWidgetGrid.Size(size.columns.coerceIn(1, max.columns), size.rows.coerceIn(1, max.rows))

/**
 * The three launcher hosts a Wear widget export generates previews for.
 *
 * An adaptive widget previews at both sizes regardless of [size]: each pane draws the design it
 * resolves to at that size (`AdaptiveWearWidget.resolve`), which is the same design its export
 * writes into that size's branch, so a pane shows exactly what that container will get.
 */
internal fun UiBuilderDocument.wearWidgetPreviewPanes(
  size: WearWidgetScaffoldSize
): List<UiBuilderVariantPane> {
  if (!AdaptiveWearWidget.isAdaptive(this)) return wearWidgetPreviewPanes(size, this, null)
  // Small first: it is the size the template has adapted, so it is what a designer checks.
  return listOf(WearWidgetScaffoldSize.Small, WearWidgetScaffoldSize.Large).flatMap {
    wearWidgetPreviewPanes(it, AdaptiveWearWidget.resolve(this, it), it)
  }
}

private fun wearWidgetPreviewPanes(
  size: WearWidgetScaffoldSize,
  document: UiBuilderDocument,
  adaptiveSize: WearWidgetScaffoldSize?,
): List<UiBuilderVariantPane> =
  WearWidgetHostShape.entries.map { shape ->
    val spec = size.hostSpec(shape)
    val host =
      // Named for the watches that draw each frame: Samsung's launcher gives a widget fully
      // rounded ends, and the Pixel Watch a rounded rectangle.
      when (shape) {
        WearWidgetHostShape.Round -> "Samsung"
        WearWidgetHostShape.Squircle -> "Pixel Watch"
        WearWidgetHostShape.Rectangular -> "Rectangular"
      }
    UiBuilderVariantPane(
      id = "preview-widget-${shape.id}" + adaptiveSize?.let { "-${it.name.lowercase()}" }.orEmpty(),
      label = adaptiveSize?.let { "$host · ${it.name}" } ?: host,
      widthDp = spec.frameWidthDp.toFloat(),
      heightDp = spec.frameHeightDp.toFloat(),
      document = document,
      wearWidgetHostShape = shape,
    )
  }

/**
 * What a compact layout's tab calls this pane. A device or the design's own frame is named alone,
 * since everything after its name is size and density that the pane's own label repeats over the
 * frame. Every other label is kept whole: a widget host's or an axis's second part is what tells
 * two tabs apart, and "Rectangular · Small" cut at its separator is "Rectangular · Large" too.
 */
internal fun UiBuilderVariantPane.mobileTabLabel(): String =
  if (
    id == CURRENT_FRAME_PANE_ID ||
      id.startsWith(DEVICE_PANE_ID_PREFIX) ||
      id.startsWith(LAUNCHER_PANE_ID_PREFIX)
  )
    label.substringBefore(" · ")
  else label

private const val CURRENT_FRAME_PANE_ID = "preview-current"

private const val DEVICE_PANE_ID_PREFIX = "variant-device-"

/** A fixed launcher grid size's pane: its tab is the cell count, `3x2`. */
private const val LAUNCHER_PANE_ID_PREFIX = "preview-launcher-size-"

/**
 * The design at its own frame, as a preview pane: the first frame of a view that has no authoring
 * canvas beside it to show the design's own size.
 */
internal fun UiBuilderDocument.currentFramePane(label: String = "Current"): UiBuilderVariantPane {
  val settings = screenEnvironmentSettings()
  return UiBuilderVariantPane(
    id = CURRENT_FRAME_PANE_ID,
    label = "$label · ${settings.widthDp}×${settings.heightDp}dp",
    widthDp = settings.widthDp.toFloat(),
    heightDp = settings.heightDp.toFloat(),
    document = this,
  )
}

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
        id = "$DEVICE_PANE_ID_PREFIX${preset.id}",
        label = deviceVariantLabel(preset),
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
          label = axis.labelFor(this),
          widthDp = settings.widthDp.toFloat(),
          heightDp = settings.heightDp.toFloat(),
          document = withEnvironmentOverrides(axis.overrides(this)),
        )
      }
  return devicePanes + axisPanes
}

/**
 * What a device pane is called: the device, and the three properties being applied.
 *
 * A device here is **not** a picture of a handset. Nothing draws a bezel, a notch or a rounded
 * corner — [UiBuilderVariantPane] is the design composed at a size and a density, and that is the
 * whole of what a preset carries ([UiBuilderDevicePreset] has an id, a label, a width, a height and
 * a density and nothing else). So the label says the properties rather than only the name: "Pixel
 * 6" alone leaves somebody comparing two panes to guess which of width, height and density moved,
 * which is the only question the pane can answer.
 *
 * The density is trimmed of a trailing `.0` because `2×` is the number a designer says and `2.0×`
 * reads like a measurement; a fractional one keeps its digits, since 2.625 and 2.75 are different
 * devices and rounding them together would make two panes claim the same frame.
 */
internal fun deviceVariantLabel(preset: UiBuilderDevicePreset): String =
  "${preset.label} · ${preset.widthDp}×${preset.heightDp}dp · ${trimmedDensity(preset.density)}×"

/** `2.0` as `2`, `2.625` unchanged — see [deviceVariantLabel]. */
internal fun trimmedDensity(density: Double): String = density.toString().removeSuffix(".0")

/** What one axis writes over the design's own environment. */
private fun EditorVariantAxis.overrides(document: UiBuilderDocument): Map<String, JsonPrimitive> =
  when (this) {
    EditorVariantAxis.Dark -> mapOf("theme" to JsonPrimitive(EditorScreenTheme.Dark.wireValue))
    EditorVariantAxis.Rtl ->
      mapOf("layoutDirection" to JsonPrimitive(EditorLayoutDirection.Rtl.wireValue))
    EditorVariantAxis.LargeFont ->
      mapOf("fontScale" to JsonPrimitive(document.largeFontVariantScale()))
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
