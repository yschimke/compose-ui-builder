package ee.schimke.composeai.uibuilder.editor

import ee.schimke.composeai.uibuilder.canvas.UiBuilderDevicePreset
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CatalogPreview
import ee.schimke.composeai.uibuilder.export.AdaptiveWearWidget
import ee.schimke.composeai.uibuilder.export.LauncherWidgetGrid
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetHostSpec
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.hostSpec
import ee.schimke.composeai.uibuilder.export.isLauncherWidget
import ee.schimke.composeai.uibuilder.export.isWearScreen
import ee.schimke.composeai.uibuilder.export.isWearWidget
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One unstored axis the variant strip draws the design on. Devices are not axes: they are stored as
 * `DesignEnvironmentV1.exportDevices` and exported as `@Preview(device = …)`. These have no stored
 * home (the environment contract is closed to this repository) and reset on reopen; see
 * [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](../../../../../../docs/design/UI_BUILDER_CANVAS_FRAMES_VARIANTS.md).
 */
enum class EditorVariantAxis(val label: String) {
  /** The design under the dark scheme, whatever its own theme says. */
  Dark("Dark"),

  /** The design mirrored, for the half of the world that reads the other way. */
  Rtl("RTL"),

  /**
   * The design at its platform's largest common accessibility font scale:
   * [LARGE_FONT_VARIANT_SCALE], or [WEAR_LARGE_FONT_VARIANT_SCALE] on a watch. [label] is the
   * phone's; see [labelFor].
   */
  LargeFont("Font 1.5×"),
}

/** The font scale [EditorVariantAxis.LargeFont] draws a phone, tablet or desktop design at. */
const val LARGE_FONT_VARIANT_SCALE: Double = 1.5

/** The largest step of Wear OS's font size setting; a watch never reaches 1.5. */
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
 * One pane of the variant strip: a title and the document to draw. A whole document because
 * [UiBuilderSurface] reads theme, density, font scale and direction from `document.environment`.
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
   * A launcher widget resized across a patch of home screen a cell at a time; see
   * [launcherWidgetPreviewPanes]. [widthDp]/[heightDp] are then the whole patch
   * ([LAUNCHER_RESIZE_MAX]) so the strip does not reflow as it grows.
   */
  val launcherGridResizable: Boolean = false,
  /**
   * A Wear widget host this pane draws around a design that has no container of its own (a catalog
   * preview; see [catalogPreviewPanes]): [widthDp]/[heightDp] are the host's frame and the design
   * is laid out in its content box.
   */
  val wearHostFrame: WearWidgetHostSpec? = null,
)

/**
 * The preview panes a widget design uses instead of the device and axis strip (Wear host shapes or
 * launcher grid sizes), or null. [resizable] adds the animated launcher pane; hosts rendering fixed
 * stills leave it out.
 */
internal fun UiBuilderDocument.widgetPreviewPanes(
  resizable: Boolean = true
): List<UiBuilderVariantPane>? =
  wearWidgetScaffoldSize()?.let(::wearWidgetPreviewPanes)
    ?: if (isLauncherWidget()) launcherWidgetPreviewPanes(resizable) else null

/**
 * The panes [previews] (the catalog's `frame.previews`) draw this design in, or null when the
 * catalog declares none or the design is itself a Wear or launcher widget, whose own host panes
 * ([widgetPreviewPanes]) already show it where it lands. [resizable] keeps the animated launcher
 * pane; hosts rendering fixed stills leave it out.
 */
fun UiBuilderDocument.catalogPreviewPanes(
  previews: List<CatalogPreview>,
  resizable: Boolean = true,
): List<UiBuilderVariantPane>? {
  if (previews.isEmpty() || wearWidgetScaffoldSize() != null || isLauncherWidget()) return null
  val own = screenEnvironmentSettings()
  return previews
    .mapNotNull { preview ->
      when (preview) {
        is CatalogPreview.WearWidget -> {
          val spec = preview.size.hostSpec(preview.shape)
          UiBuilderVariantPane(
            id = "preview-catalog-wear-${preview.size.name.lowercase()}-${preview.shape.id}",
            label = preview.label ?: "${wearHostName(preview.shape)} · ${preview.size.name}",
            widthDp = spec.frameWidthDp.toFloat(),
            heightDp = spec.frameHeightDp.toFloat(),
            document =
              withEnvironmentOverrides(
                mapOf(
                  "widthDp" to JsonPrimitive(spec.contentWidthDp),
                  "heightDp" to JsonPrimitive(spec.contentHeightDp),
                )
              ),
            wearWidgetHostShape = preview.shape,
            wearHostFrame = spec,
          )
        }
        is CatalogPreview.LauncherResizable ->
          if (!resizable) null
          else {
            val patch = launcherGridPatchDp(LAUNCHER_RESIZE_MAX)
            UiBuilderVariantPane(
              id = LAUNCHER_RESIZABLE_PANE_ID,
              label = preview.label ?: "Resizable",
              widthDp = patch.first,
              heightDp = patch.second,
              document = this,
              launcherGridResizable = true,
            )
          }
        is CatalogPreview.Frame -> {
          val height = preview.heightDp ?: own.heightDp
          UiBuilderVariantPane(
            id = "preview-catalog-frame-${preview.widthDp}x${preview.heightDp ?: "own"}",
            label = (preview.label ?: "Frame") + " · ${preview.widthDp}×${height}dp",
            widthDp = preview.widthDp.toFloat(),
            heightDp = height.toFloat(),
            document =
              withEnvironmentOverrides(
                mapOf(
                  "widthDp" to JsonPrimitive(preview.widthDp),
                  "heightDp" to JsonPrimitive(height),
                )
              ),
          )
        }
      }
    }
    .distinctBy { it.id }
    .takeIf { it.isNotEmpty() }
}

/** What a Wear host shape is called on a pane: the watch that draws it. */
private fun wearHostName(shape: WearWidgetHostShape): String =
  when (shape) {
    WearWidgetHostShape.Round -> "Samsung"
    WearWidgetHostShape.Squircle -> "Pixel Watch"
    WearWidgetHostShape.Rectangular -> "Rectangular"
  }

/**
 * The fixed grid sizes a launcher widget is previewed at (narrow strip, square, two wide), plus the
 * design's own size when it is none of these.
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
 * A launcher widget's panes: the resizing one (when [resizable]), then one per
 * [LAUNCHER_PREVIEW_SIZES] entry, smallest first, each at that size's reference dp. Labels give
 * both cell count and dp.
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
  // A frame off the grid is still a frame the export writes (`LauncherWidgetCodeExporter` keeps
  // the authored dp), so the size being authored is shown as it is rather than dropped.
  val offGrid =
    if (own != null) emptyList()
    else
      screenEnvironmentSettings().let { settings ->
        listOf(
          UiBuilderVariantPane(
            id = CURRENT_FRAME_PANE_ID,
            label = "Current · ${settings.widthDp}×${settings.heightDp}dp",
            widthDp = settings.widthDp.toFloat(),
            heightDp = settings.heightDp.toFloat(),
            document = this,
          )
        )
      }
  if (!resizable) return offGrid + fixed
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
  ) + offGrid + fixed
}

/** The cell count this design is sized at, or null when its frame is not a grid size. */
internal fun UiBuilderDocument.launcherGridSize(): LauncherWidgetGrid.Size? {
  val settings = screenEnvironmentSettings()
  return LauncherWidgetGrid.of(settings.widthDp, settings.heightDp)
}

/** The cell count nearest this design's frame, where the resizable pane starts. */
internal fun UiBuilderDocument.nearestLauncherGridSize(): LauncherWidgetGrid.Size {
  val settings = screenEnvironmentSettings()
  return clampLauncherSize(
    LauncherWidgetGrid.Size(
      ((settings.widthDp + LauncherWidgetGrid.CELL_MARGIN_DP).toFloat() /
          LauncherWidgetGrid.CELL_WIDTH_DP)
        .roundToInt()
        .coerceAtLeast(1),
      ((settings.heightDp + LauncherWidgetGrid.CELL_MARGIN_DP).toFloat() /
          LauncherWidgetGrid.CELL_HEIGHT_DP)
        .roundToInt()
        .coerceAtLeast(1),
    )
  )
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
 * The home-screen patch [size] cells cover, in dp, margins included. The widget's own dp
 * ([LauncherWidgetGrid.Size.widthDp]) is this less one [LauncherWidgetGrid.CELL_MARGIN_DP].
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
 * The three launcher hosts a Wear widget export generates previews for. Adaptive widgets preview at
 * both sizes, each pane resolving the design (`AdaptiveWearWidget.resolve`) as the export does.
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
    // Named for the watches that draw each frame: Samsung's launcher gives a widget fully rounded
    // ends, and the Pixel Watch a rounded rectangle.
    val host = wearHostName(shape)
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
 * A compact tab's label: a device or the design's own frame by name alone (the pane repeats the
 * size); other labels whole, since their second part tells tabs apart.
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
 * The design at its own frame as a preview pane, for views with no authoring canvas beside them.
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
 * The panes to draw beside the editing pane, in order: devices (the design's claim), then unstored
 * axes. Devices without a host preset are skipped, since a preset carries the only geometry. Every
 * pane is read-only.
 */
fun UiBuilderDocument.variantPanes(
  presets: List<UiBuilderDevicePreset>,
  axes: Set<EditorVariantAxis>,
): List<UiBuilderVariantPane> {
  val settings = screenEnvironmentSettings()
  val devicePanes =
    // Distinct: `exportDevices` is stored verbatim and protocol/MCP writers may repeat ids.
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
 * A device pane's label: the device plus width, height and density, since a pane is only the design
 * at those properties (no handset is drawn). Density drops a trailing `.0` but keeps fractional
 * digits.
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
 * This document with environment fields overridden. Id and revision are untouched:
 * [UiBuilderSurface] keys on them plus `renderSessionId`, and a new id would discard the
 * composition on every edit. Numbers are written as strings, as every environment reader parses
 * text.
 */
internal fun UiBuilderDocument.withEnvironmentOverrides(
  overrides: Map<String, JsonPrimitive>
): UiBuilderDocument =
  copy(
    environment =
      JsonObject(environment + overrides.mapValues { (_, value) -> JsonPrimitive(value.content) })
  )
