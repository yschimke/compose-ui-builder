package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.REMOTE_TEXT_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * A label in a `layout/box` with `contentAlignment = center` is drawn centred in the widget's PNG,
 * with no `alignment` of its own.
 *
 * The structure is the one yschimke/remote-m3-catalog#12 reported drawing top-left: a weighted,
 * full-height `m3/surface` in a row, holding a `fillMaxSize` box that centres its text. The PNG
 * export draws through this surface with the document's packaged catalog provided
 * (`ProductionUiBuilderSurface`), so both are measured: with and without that catalog, for the
 * borrowed `m3/text` and the published `remote-m3/remote-text`.
 */
class WidgetBoxContentAlignmentTest {

  @Test
  fun `a box's contentAlignment centres its text in the exported widget`() {
    for (text in listOf("m3/text", REMOTE_TEXT_COMPONENT_ID)) {
      val document = launcherButton(text)
      val catalog = assertNotNull(productionPreviewCatalog(document), "packaged remote-m3 catalog")
      val renders =
        listOf(JvmDocumentRasterizer.renderPng(document, catalog)) +
          // The bare surface draws only the ids it has its own branch for, `m3/text` among them.
          listOfNotNull(JvmDocumentRasterizer.renderPng(document).takeIf { text == "m3/text" })
      for (png in renders) {
        val label = assertNotNull(labelBounds(png), "$text drew no label")
        // The surface fills the widget's content box, so its centre is the frame's.
        val centreX = (label.left + label.right) / 2f
        val centreY = (label.top + label.bottom) / 2f
        assertTrue(abs(centreX - FRAME_WIDTH / 2f) <= 4f, "$text centred across: $label")
        assertTrue(abs(centreY - FRAME_HEIGHT / 2f) <= 4f, "$text centred down: $label")
      }
    }
  }

  private data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

  /** Where the white label is, on the surface's blue. */
  private fun labelBounds(png: ByteArray): Bounds? {
    val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
    var bounds: Bounds? = null
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        val color = bitmap.getColor(x, y)
        val white = listOf(16, 8, 0).all { shift -> (color shr shift) and 0xff > 200 }
        if (!white) continue
        bounds =
          bounds?.let { Bounds(minOf(it.left, x), minOf(it.top, y), maxOf(it.right, x), y) }
            ?: Bounds(x, y, x, y)
      }
    }
    return bounds
  }

  private fun launcherButton(text: String) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "launcher-button",
      title = "Launcher button",
      revision = 0,
      catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("remote-m3"))),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(FRAME_WIDTH),
            "heightDp" to JsonPrimitive(FRAME_HEIGHT),
            "density" to JsonPrimitive(1.0),
            "fontScale" to JsonPrimitive(1.0),
            "theme" to JsonPrimitive("dark"),
            "locale" to JsonPrimitive("en-US"),
            "layoutDirection" to JsonPrimitive("ltr"),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("host"),
      nodes =
        mapOf(
          "host" to
            UiBuilderNode(
              "host",
              "remote-m3/widget-container-small",
              slots = mapOf("content" to listOf("row")),
            ),
          "row" to
            UiBuilderNode(
              "row",
              "layout/row",
              modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
              slots = mapOf("children" to listOf("surface")),
            ),
          "surface" to
            UiBuilderNode(
              "surface",
              "m3/surface",
              properties = JsonObject(mapOf("containerColor" to literal("color", "#FF0000FF"))),
              modifiers =
                JsonArray(
                  listOf(
                    modifier("weight", "weight" to JsonPrimitive(1)),
                    modifier("fillMaxHeight"),
                  )
                ),
              slots = mapOf("content" to listOf("box")),
            ),
          "box" to
            UiBuilderNode(
              "box",
              "layout/box",
              properties = JsonObject(mapOf("contentAlignment" to literal("enum", "center"))),
              modifiers = JsonArray(listOf(modifier("fillMaxSize"))),
              slots = mapOf("children" to listOf("label")),
            ),
          "label" to
            UiBuilderNode(
              "label",
              text,
              properties =
                JsonObject(
                  mapOf(
                    "text" to literal("string", "Home"),
                    "color" to literal("color", "#FFFFFFFF"),
                  )
                ),
            ),
        ),
    )

  private fun literal(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private fun modifier(type: String, vararg extra: Pair<String, JsonElement>) =
    JsonObject(mapOf("type" to JsonPrimitive(type)) + extra)

  private companion object {
    const val FRAME_WIDTH = 216
    const val FRAME_HEIGHT = 76
  }
}
