package ee.schimke.composeai.uibuilder.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The frame a catalog declares for its screens: the contract's `frame` block
 * (`statusSemantics.frame`), as data. The catalog measures its insets with the real
 * `ScreenScaffold`; the renderer reads them through [LocalUiBuilderFrameGeometry] rather than
 * hard-coding them. A catalog that declares nothing gets [None]: the document's frame with no
 * insets.
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
   * The discrete frame sizes the catalog's designs are authored at (`geometry.sizesDp`), in catalog
   * order: launcher grid sizes (`3x2`, `2x1`) or Wear widget host containers. Empty for
   * device-sized screen catalogs.
   */
  val sizes: List<FrameSize> = emptyList(),
) {
  /**
   * The [sizes] as frame-picker entries at [density], under [CATALOG_SIZES_GROUP]. Width and height
   * change; density stays (a grid cell isn't a device).
   */
  fun sizePresets(density: Double): List<UiBuilderDevicePreset> = sizes.map {
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
   * The diameter a design is drawn at, given the frame its document names: its width if within the
   * declared range, otherwise (a phone frame, or nothing declared) the smallest declared diameter.
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

    /**
     * Never a `@Preview(device = …)` token: a catalog size is a width and a height, not a device.
     */
    const val CATALOG_SIZE_ID_PREFIX: String = "size:"

    /**
     * The `frame` block of a served catalog's `statusSemantics`, or [None]. Tolerant: an unreadable
     * frame isn't drawn, rather than blanking the editor.
     */
    fun from(statusSemantics: JsonObject): UiBuilderFrameGeometry {
      val frame = statusSemantics["frame"] as? JsonObject ?: return None
      val geometry = frame["geometry"] as? JsonObject
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
            // A row this build cannot read is a size it does not offer, never an exception.
            val width = size["widthDp"].intOrNull() ?: return@mapNotNull null
            val height = size["heightDp"].intOrNull() ?: return@mapNotNull null
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

private fun JsonElement?.intOrNull(): Int? =
  if (this == null) null else runCatching { jsonPrimitive.intOrNull }.getOrNull()

private fun JsonElement?.contentOrEmpty(): String =
  if (this == null) "" else runCatching { jsonPrimitive.content }.getOrDefault("")
