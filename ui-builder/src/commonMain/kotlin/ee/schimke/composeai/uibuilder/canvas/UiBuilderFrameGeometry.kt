package ee.schimke.composeai.uibuilder.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frame a catalog declares for its screens — the contract's `frame` block, as data.
 *
 * ## Why this is here rather than in the renderer
 *
 * A catalog knows what its screens look like: which drawing frames them, how much room the content
 * gets inside that drawing, what a new design opens on. This build knows how to *draw* a round
 * screen; it does not know that a watch's content is inset 10dp at 192. Those numbers were measured
 * in the catalog's own repository by a test that composes the real `ScreenScaffold`, and they were
 * then transcribed into the renderer as constants — so the same fact lived twice, in a file a test
 * writes and in a file a person edits, held equal by nobody.
 *
 * The catalog publishes them under `statusSemantics.frame`; this is the reader, and the renderer
 * consumes it through [LocalUiBuilderFrameGeometry] the way it consumes `canvas` adapters through
 * `LocalUiBuilderCanvasAdapters`. A catalog that declares nothing gets [None], which draws the
 * frame the document names with no insets rather than inventing one.
 *
 * @param adapter the canvas drawing that frames this catalog's screen root, e.g.
 *   `frame/round-screen`. Empty when the catalog declares none.
 * @param seedDevice the `@Preview(device = …)` token a new design opens on, when the catalog names
 *   one. The host resolves it against its device presets.
 * @param contentPadding the measured insets, ascending by diameter. Empty when the catalog declares
 *   none.
 */
data class UiBuilderFrameGeometry(
  val adapter: String = "",
  val seedDevice: String? = null,
  val contentPadding: List<ContentPadding> = emptyList(),
  /**
   * The discrete frame sizes the catalog's designs are authored at — `geometry.sizesDp` — in the
   * order the catalog lists them. A launcher widget catalog names its launcher grid sizes here
   * (`3x2`, `2x1`); a Wear widget catalog its host containers. Empty for a catalog sized by device,
   * which is every screen catalog.
   */
  val sizes: List<FrameSize> = emptyList(),
) {
  /**
   * The [sizes] as frame-picker entries at [density], under [CATALOG_SIZES_GROUP]. A catalog size
   * changes the width and height and keeps the design's density: a grid cell is a dp rectangle,
   * not a device.
   */
  fun sizePresets(density: Double): List<UiBuilderDevicePreset> =
    sizes.map {
      UiBuilderDevicePreset(
        id = "$CATALOG_SIZE_ID_PREFIX${it.label}",
        label = it.label,
        group = CATALOG_SIZES_GROUP,
        widthDp = it.widthDp,
        heightDp = it.heightDp,
        density = density,
      )
    }

  /** One size the catalog declares: `{ "widthDp": 203, "heightDp": 220, "label": "3x2" }`. */
  data class FrameSize(val widthDp: Int, val heightDp: Int, val label: String)

  /** What a screen of [screenDp] is inset by, interpolated between the measured rows. */
  fun paddingFor(screenDp: Int): ContentPadding =
    when {
      contentPadding.isEmpty() -> ContentPadding(screenDp, 0f, 0f)
      screenDp <= contentPadding.first().screenDp -> contentPadding.first()
      screenDp >= contentPadding.last().screenDp -> contentPadding.last()
      else -> {
        val upper = contentPadding.indexOfFirst { it.screenDp >= screenDp }
        val low = contentPadding[upper - 1]
        val high = contentPadding[upper]
        val t = (screenDp - low.screenDp).toFloat() / (high.screenDp - low.screenDp)
        ContentPadding(
          screenDp = screenDp,
          horizontalDp = low.horizontalDp + t * (high.horizontalDp - low.horizontalDp),
          verticalDp = low.verticalDp + t * (high.verticalDp - low.verticalDp),
        )
      }
    }

  /**
   * The diameter a design is drawn at, given the frame its document names.
   *
   * A width inside the declared range is itself, which is what makes a `wearos_xl_round` pane draw
   * a 240dp watch rather than a scaled 192dp one. Outside it — a phone frame, or a catalog that
   * declares nothing — the smallest declared diameter is the answer, because a design opened on a
   * phone is a design nobody has picked a watch for yet and a 411dp watch is worse than the
   * smallest real one.
   */
  fun diameterFor(widthDp: Int?): Int =
    when {
      contentPadding.isEmpty() -> widthDp ?: REFERENCE_DIAMETER_DP
      widthDp != null &&
        widthDp in contentPadding.first().screenDp..contentPadding.last().screenDp -> widthDp
      else -> contentPadding.first().screenDp
    }

  /** One measured row of the catalog's content-padding table. */
  data class ContentPadding(
    val screenDp: Int,
    val horizontalDp: Float,
    val verticalDp: Float,
  )

  companion object {
    /** The diameter a catalog that declares no geometry is drawn at when its frame names none. */
    const val REFERENCE_DIAMETER_DP: Int = 192

    val None = UiBuilderFrameGeometry()

    /** The frame-picker section a catalog's own [sizes] are listed under. */
    const val CATALOG_SIZES_GROUP: String = "Catalog sizes"

    /** Never a `@Preview(device = …)` token: a catalog size is a width and a height, not a device. */
    const val CATALOG_SIZE_ID_PREFIX: String = "size:"

    /**
     * The `frame` block of a served catalog's `statusSemantics`, or [None].
     *
     * Tolerant on purpose: this reads a document another repository publishes, and a field it has
     * not grown yet, or has renamed, must not take the canvas down. A frame this build cannot read
     * is a frame it does not draw, which is visible and fixable, where an exception is a blank
     * editor.
     */
    fun from(statusSemantics: JsonObject): UiBuilderFrameGeometry {
      val frame = statusSemantics["frame"]?.jsonObject ?: return None
      val geometry = frame["geometry"]?.jsonObject
      val rows =
        geometry
          ?.get("contentPadding")
          ?.jsonArray
          ?.mapNotNull { row ->
            val entry = row.jsonObject
            val screenDp = entry["screenDp"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            ContentPadding(
              screenDp = screenDp,
              horizontalDp = entry["horizontalDp"]?.jsonPrimitive?.floatOrNull ?: 0f,
              verticalDp = entry["verticalDp"]?.jsonPrimitive?.floatOrNull ?: 0f,
            )
          }
          .orEmpty()
          .sortedBy { it.screenDp }
      val sizes =
        geometry
          ?.get("sizesDp")
          ?.let { runCatching { it.jsonArray }.getOrNull() }
          ?.mapNotNull { entry ->
            val size = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val width = size["widthDp"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val height = size["heightDp"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (width <= 0 || height <= 0) return@mapNotNull null
            FrameSize(
              widthDp = width,
              heightDp = height,
              label = size["label"].contentOrEmpty().ifEmpty { "$width × $height dp" },
            )
          }
          .orEmpty()
      return UiBuilderFrameGeometry(
        adapter = frame["adapter"].contentOrEmpty(),
        seedDevice = frame["seedDevice"].contentOrEmpty().takeIf { it.isNotEmpty() },
        contentPadding = rows,
        sizes = sizes,
      )
    }
  }
}

private fun JsonElement?.contentOrEmpty(): String =
  if (this == null) "" else runCatching { jsonPrimitive.content }.getOrDefault("")
