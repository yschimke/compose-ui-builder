package ee.schimke.composeai.uibuilder.capability

import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * One preview a catalog asks every design to be shown at (`frame.previews` in its policy, carried
 * verbatim in `statusSemantics.frame`). A catalog whose designs render in several hosts — a
 * dashboard card that is also a Wear widget and a launcher widget — lists them, so the strip beside
 * a design shows it everywhere it lands rather than at the one frame it is authored at.
 */
sealed interface CatalogPreview {
  /** The preview's own label, or null for the builder's default. */
  val label: String?

  /** The design in a Wear widget host: [size]'s content box inside [shape]'s container. */
  data class WearWidget(
    val size: WearWidgetScaffoldSize,
    val shape: WearWidgetHostShape,
    override val label: String? = null,
  ) : CatalogPreview

  /** The design as a launcher widget, resized across the home screen a cell at a time. */
  data class LauncherResizable(override val label: String? = null) : CatalogPreview

  /** The design at a fixed width, and at [heightDp] or its own height when that is null. */
  data class Frame(val widthDp: Int, val heightDp: Int?, override val label: String? = null) :
    CatalogPreview

  companion object {
    /**
     * The previews [statusSemantics] declares under `frame.previews`, in order. An entry this build
     * cannot read is a preview it does not show, never an exception; none declared is empty.
     */
    fun from(statusSemantics: JsonObject): List<CatalogPreview> {
      val previews = (statusSemantics["frame"] as? JsonObject)?.get("previews") as? JsonArray
      return previews.orEmpty().mapNotNull { (it as? JsonObject)?.let(::preview) }
    }

    private fun preview(entry: JsonObject): CatalogPreview? {
      val label = entry.text("label")
      return when (entry.text("kind")) {
        "wear-widget" -> {
          val size =
            WearWidgetScaffoldSize.entries.firstOrNull {
              it.name.equals(entry.text("size"), ignoreCase = true)
            } ?: return null
          val shape =
            WearWidgetHostShape.entries.firstOrNull { it.id == entry.text("shape") } ?: return null
          WearWidget(size, shape, label)
        }
        "launcher-resizable" -> LauncherResizable(label)
        "frame" -> {
          val width = entry.int("widthDp")?.takeIf { it > 0 } ?: return null
          Frame(width, entry.int("heightDp")?.takeIf { it > 0 }, label)
        }
        else -> null
      }
    }

    private fun JsonObject.text(name: String): String? =
      (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
  }
}
