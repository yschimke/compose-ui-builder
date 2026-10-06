package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.export.REMOTE_ICON_COMPONENT_ID
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.svg.JvmDocumentRasterizer
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image

/**
 * `remote-m3/remote-icon` draws on the canvas with what the design authored under `RemoteIcon`'s
 * own names. The `wear-m3/icon` adapter reads `iconKey` and `color`; the catalog mapping is what
 * routes `imageVector` and `tint` to them, and without the second the canvas drew every icon in the
 * content colour while the export wrote the tint.
 */
class RemoteIconCanvasTest {

  @Test
  fun `an icon's tint reaches the canvas`() {
    val document = widget(tint = "#FFFF0000")
    val catalog = assertNotNull(productionPreviewCatalog(document), "packaged remote-m3 catalog")
    val png = JvmDocumentRasterizer.renderPng(document, catalog)

    assertTrue(redPixels(png) > 20, "the tinted icon drew no red")
  }

  private fun redPixels(png: ByteArray): Int {
    val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
    var count = 0
    for (y in 0 until bitmap.height) {
      for (x in 0 until bitmap.width) {
        val color = bitmap.getColor(x, y)
        val red = (color shr 16) and 0xff
        val green = (color shr 8) and 0xff
        val blue = color and 0xff
        if (red > 200 && green < 60 && blue < 60) count++
      }
    }
    return count
  }

  private fun widget(tint: String) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "remote-icon",
      title = "Remote icon",
      revision = 0,
      catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("remote-m3"))),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(216),
            "heightDp" to JsonPrimitive(76),
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
              slots = mapOf("content" to listOf("icon")),
            ),
          "icon" to
            UiBuilderNode(
              "icon",
              REMOTE_ICON_COMPONENT_ID,
              properties =
                JsonObject(
                  mapOf(
                    "imageVector" to literal("enum", "home"),
                    "tint" to literal("color", tint),
                  )
                ),
            ),
        ),
    )

  private fun literal(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))
}
