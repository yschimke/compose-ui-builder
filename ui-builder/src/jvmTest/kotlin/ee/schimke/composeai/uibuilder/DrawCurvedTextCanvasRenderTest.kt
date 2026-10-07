package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Curved text on the editing canvas: round a circle, centred on its angle, and along a path. */
@OptIn(ExperimentalTestApi::class)
class DrawCurvedTextCanvasRenderTest {
  /** How many red samples the text drew, and their mean height on screen. */
  private fun red(label: String): Pair<Int, Double> {
    var count = 0
    var ys = 0.0
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(document(label))) }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width) {
        for (y in 0 until pixels.height) {
          val pixel = pixels[x, y]
          if (pixel.red > 0.8f && pixel.green < 0.3f && pixel.blue < 0.3f) {
            count++
            ys += y
          }
        }
      }
    }
    return count to if (count == 0) 0.0 else ys / count
  }

  private fun circle(angle: Int) =
    """{"id":"label","componentId":"draw/text-circle","properties":{
      "text":{"type":"string","value":"ROUND"},
      "angle":{"type":"float","value":$angle},
      "textSizeSp":{"type":"float","value":12},
      "color":{"type":"color","value":"#FFFF0000"}},"modifiers":[],"slots":{},"eventBindings":{}}"""

  @Test
  fun `text on a circle sits where its angle says`() {
    val (top, topY) = red(circle(270))
    val (bottom, bottomY) = red(circle(90))

    assertTrue(top > 20 && bottom > 20, "top $top, bottom $bottom samples")
    assertTrue(topY < bottomY - 20, "top at $topY, bottom at $bottomY")
    // The same glyphs, turned: about as much ink either way.
    assertTrue(abs(top - bottom) < top / 3, "top $top, bottom $bottom")
  }

  @Test
  fun `text on a path follows it`() {
    val (count, _) =
      red(
        """{"id":"label","componentId":"draw/text-path","properties":{
          "text":{"type":"string","value":"ALONG"},
          "pathData":{"type":"string","value":"M8 70 Q50 10 92 70"},
          "textSizeSp":{"type":"float","value":12},
          "color":{"type":"color","value":"#FFFF0000"}},"modifiers":[],"slots":{},"eventBindings":{}}"""
      )

    assertTrue(count > 20, "no text along the path ($count samples)")
  }

  private fun document(label: String): String =
    """
    {"schema":"compose-ui-builder-document/v1-candidate","id":"curves","title":"Curves",
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
        "modifiers":[{"type":"size","widthDp":100,"heightDp":100}],
        "slots":{"ops":["label"]},"eventBindings":{}},
      "label":$label}}
    """
      .trimIndent()
}
