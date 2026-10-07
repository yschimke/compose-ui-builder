package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.UiDrawing
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A `draw/canvas` on the editing canvas draws its operations — the same shapes, in the same place,
 * the exported `RemoteCanvas` draws — rather than an "Unsupported component" box.
 */
@OptIn(ExperimentalTestApi::class)
class DrawCanvasRenderTest {
  private val document = decodeProductionRendererDocument(DOCUMENT)

  @Test
  fun `the remote-m3 palette offers the drawing vocabulary`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
      )

    UiDrawing.COMPONENT_IDS.forEach { id ->
      assertNotNull(catalog.componentsById[id], "$id is not on the remote-m3 palette")
    }
    val ops = catalog.componentsById.getValue(UiDrawing.CANVAS).slots.single()
    assertTrue(UiDrawing.TRAIT in ops.acceptedTraits, ops.toString())
  }

  @Test
  fun `a canvas draws its operations`() =
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(document) }

      val pixels = onRoot().captureToImage().toPixelMap()
      var red = 0
      var green = 0
      for (x in 0 until pixels.width step 2) {
        for (y in 0 until pixels.height step 2) {
          val pixel = pixels[x, y]
          if (pixel.isNear(Color.Red)) red++
          if (pixel.isNear(Color.Green)) green++
        }
      }
      // A filled square and a stroked ring, both of which only the drawing can have put there.
      assertTrue(red > 100, "no filled rectangle drawn ($red red samples)")
      assertTrue(green > 10, "no stroked circle drawn ($green green samples)")
    }

  private fun Color.isNear(other: Color): Boolean =
    kotlin.math.abs(red - other.red) < 0.1f &&
      kotlin.math.abs(green - other.green) < 0.1f &&
      kotlin.math.abs(blue - other.blue) < 0.1f

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"drawing","title":"Drawing",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["canvas"]},"eventBindings":{}},
        "canvas":{"id":"canvas","componentId":"draw/canvas","properties":{},
          "modifiers":[{"type":"size","widthDp":80,"heightDp":80}],
          "slots":{"ops":["square","ring"]},"eventBindings":{}},
        "square":{"id":"square","componentId":"draw/rect","properties":{
          "xDp":{"type":"float","value":20},"yDp":{"type":"float","value":20},
          "widthDp":{"type":"float","value":40},"heightDp":{"type":"float","value":40},
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "ring":{"id":"ring","componentId":"draw/circle","properties":{
          "style":{"type":"enum","value":"stroke"},
          "strokeWidthDp":{"type":"float","value":4},
          "color":{"type":"color","value":"#FF00FF00"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
