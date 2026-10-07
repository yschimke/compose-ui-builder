package ee.schimke.composeai.uibuilder

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
 * On the editing canvas a path clip keeps its operations inside the path, and a morph draws the
 * path its progress has reached.
 */
@OptIn(ExperimentalTestApi::class)
class DrawPathCanvasRenderTest {
  private fun red(ops: String, nodes: String): Int {
    var count = 0
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent {
        ProductionUiBuilderSurface(decodeProductionRendererDocument(document(ops, nodes)))
      }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width step 2) {
        for (y in 0 until pixels.height step 2) {
          val pixel = pixels[x, y]
          if (abs(pixel.red - 1f) < 0.1f && abs(pixel.green) < 0.1f && abs(pixel.blue) < 0.1f)
            count++
        }
      }
    }
    return count
  }

  private val square =
    """"square":{"id":"square","componentId":"draw/rect","properties":{
      "color":{"type":"color","value":"#FFFF0000"}},"modifiers":[],"slots":{},"eventBindings":{}}"""

  @Test
  fun `a path clip keeps its operations inside the path`() {
    val whole = red("\"square\"", square)
    // A triangle on the diagonal: half the canvas.
    val clipped =
      red(
        "\"lens\"",
        """"lens":{"id":"lens","componentId":"draw/clip","properties":{
          "pathData":{"type":"string","value":"M0 0 L24 0 L24 24 Z"}},
          "modifiers":[],"slots":{"ops":["square"]},"eventBindings":{}},
        $square""",
      )

    assertTrue(whole > 100, "the square is missing ($whole samples)")
    assertTrue(abs(clipped * 2 - whole) <= whole / 8, "whole $whole, clipped $clipped")
  }

  @Test
  fun `a morph draws the path its progress has reached`() {
    fun morph(progress: Float) =
      red(
        "\"grow\"",
        """"grow":{"id":"grow","componentId":"draw/morph","properties":{
          "pathData":{"type":"string","value":"M12 12 L12 12 L12 12 Z"},
          "toPathData":{"type":"string","value":"M0 0 L24 0 L24 24 Z"},
          "progress":{"type":"float","value":$progress},
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}}""",
      )

    assertEquals(0, morph(0f))
    val half = morph(0.5f)
    val full = morph(1f)
    assertTrue(full > 100, "the morphed triangle is missing ($full samples)")
    // Each side halves, so the area quarters.
    assertTrue(abs(half * 4 - full) <= full / 6, "half $half, full $full")
  }

  private fun document(ops: String, nodes: String): String =
    """
    {"schema":"compose-ui-builder-document/v1-candidate","id":"paths","title":"Paths",
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
        "slots":{"ops":[$ops]},"eventBindings":{}},
      $nodes}}
    """
      .trimIndent()
}
