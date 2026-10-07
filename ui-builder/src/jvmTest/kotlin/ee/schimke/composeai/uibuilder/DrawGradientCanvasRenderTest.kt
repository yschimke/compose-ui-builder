package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A gradient paint on the editing canvas runs from the shape's `color` to its `gradientColor`
 * across the canvas, so both ends' colours appear; without one the shape is its plain colour.
 */
@OptIn(ExperimentalTestApi::class)
class DrawGradientCanvasRenderTest {
  private fun colours(gradient: String?): Pair<Int, Int> {
    var red = 0
    var blue = 0
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent {
        ProductionUiBuilderSurface(decodeProductionRendererDocument(document(gradient)))
      }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width) {
        for (y in 0 until pixels.height step 2) {
          val pixel = pixels[x, y]
          if (pixel.isNear(Color.Red)) red++
          if (pixel.isNear(Color.Blue)) blue++
        }
      }
    }
    return red to blue
  }

  @Test
  fun `a horizontal gradient runs from colour to gradient colour`() {
    val (red, blue) = colours("horizontal")

    assertTrue(red > 0 && blue > 0, "red $red, blue $blue")
  }

  @Test
  fun `without a gradient the shape is its colour`() {
    val (red, blue) = colours(null)

    assertTrue(red > 100, "red $red")
    assertEquals(0, blue)
  }

  private fun Color.isNear(other: Color): Boolean =
    abs(red - other.red) < 0.1f && abs(green - other.green) < 0.1f && abs(blue - other.blue) < 0.1f

  private fun document(gradient: String?): String {
    val paint =
      if (gradient == null) ""
      else
        """,
          "gradient":{"type":"enum","value":"$gradient"},
          "gradientColor":{"type":"color","value":"#FF0000FF"}"""
    return """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"gradient","title":"Gradient",
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
          "modifiers":[{"type":"size","widthDp":160,"heightDp":60}],
          "slots":{"ops":["band"]},"eventBindings":{}},
        "band":{"id":"band","componentId":"draw/rect","properties":{
          "color":{"type":"color","value":"#FFFF0000"}$paint},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
      .trimIndent()
  }
}
