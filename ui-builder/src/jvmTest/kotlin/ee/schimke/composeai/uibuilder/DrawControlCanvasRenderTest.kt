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
 * On the editing canvas a `draw/repeat` draws its operations once per index with `@i` bound, a
 * `draw/clip` keeps them inside (or, excluding, outside) its box, and a `draw/if` draws them only
 * while its condition holds — what the exported `loop`, `clipRect` and `drawConditionally` do.
 */
@OptIn(ExperimentalTestApi::class)
class DrawControlCanvasRenderTest {
  /** Samples of each colour the drawing put on screen, counted every other pixel. */
  private fun colours(json: String): Map<Color, Int> {
    val counts = mutableMapOf(Color.Red to 0, Color.Green to 0, Color.Blue to 0)
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(json)) }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width step 2) {
        for (y in 0 until pixels.height step 2) {
          val pixel = pixels[x, y]
          counts.keys.toList().forEach { colour ->
            if (pixel.isNear(colour)) counts[colour] = counts.getValue(colour) + 1
          }
        }
      }
    }
    return counts
  }

  @Test
  fun `a repeat draws once per index, each pass reading its own index`() {
    val once = colours(document(until = 1)).getValue(Color.Red)
    val thrice = colours(document(until = 3)).getValue(Color.Red)

    assertTrue(once > 20, "the first square is missing ($once samples)")
    // Three squares side by side, not one square drawn three times in the same place.
    assertTrue(abs(thrice - once * 3) <= once / 4, "once $once, thrice $thrice")
  }

  @Test
  fun `a clip keeps its operations inside its box, and excluding keeps them outside`() {
    val whole = colours(document(clip = false)).getValue(Color.Green)
    val inside = colours(document()).getValue(Color.Green)
    val outside = colours(document(exclude = true)).getValue(Color.Green)

    assertTrue(whole > 100, "the clipped square is missing ($whole samples)")
    // The clip is the top half of the square.
    assertTrue(abs(inside * 2 - whole) <= whole / 8, "whole $whole, inside $inside")
    assertTrue(abs(inside + outside - whole) <= whole / 8, "inside $inside, outside $outside")
  }

  @Test
  fun `a conditional draws only while its state is true`() {
    assertTrue(colours(document(shown = true)).getValue(Color.Blue) > 20)
    assertEquals(0, colours(document(shown = false)).getValue(Color.Blue))
  }

  private fun Color.isNear(other: Color): Boolean =
    abs(red - other.red) < 0.1f && abs(green - other.green) < 0.1f && abs(blue - other.blue) < 0.1f

  /**
   * A 200 by 100 canvas: [until] red squares 20dp apart across the top-left, a green square clipped
   * to its top half on the right, and a blue dot shown while `shown` is true.
   */
  private fun document(
    until: Int = 3,
    clip: Boolean = true,
    exclude: Boolean = false,
    shown: Boolean = true,
  ): String {
    val canvasOps =
      listOfNotNull("\"squares\"", if (clip) "\"window\"" else "\"panel\"", "\"alarm\"")
        .joinToString(",")
    return """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"control","title":"Control",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{"shown":{"type":"value","valueType":"bool","initialValue":$shown,
         "nullable":false,"persistence":"session"}},
       "roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["canvas"]},"eventBindings":{}},
        "canvas":{"id":"canvas","componentId":"draw/canvas","properties":{},
          "modifiers":[{"type":"size","widthDp":200,"heightDp":100}],
          "slots":{"ops":[$canvasOps]},"eventBindings":{}},
        "squares":{"id":"squares","componentId":"draw/repeat","properties":{
          "until":{"type":"float","value":$until}},
          "modifiers":[],"slots":{"ops":["square"]},"eventBindings":{}},
        "square":{"id":"square","componentId":"draw/rect","properties":{
          "xDp":{"type":"expr","op":"mul","args":[
            {"type":"binding","value":"i"},{"type":"int","value":20}]},
          "yDp":{"type":"float","value":0},
          "widthDp":{"type":"float","value":10},"heightDp":{"type":"float","value":10},
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "window":{"id":"window","componentId":"draw/clip","properties":{
          "xDp":{"type":"float","value":100},"yDp":{"type":"float","value":20},
          "widthDp":{"type":"float","value":40},"heightDp":{"type":"float","value":20},
          "exclude":{"type":"bool","value":$exclude}},
          "modifiers":[],"slots":{"ops":["panel"]},"eventBindings":{}},
        "panel":{"id":"panel","componentId":"draw/rect","properties":{
          "xDp":{"type":"float","value":100},"yDp":{"type":"float","value":20},
          "widthDp":{"type":"float","value":40},"heightDp":{"type":"float","value":40},
          "color":{"type":"color","value":"#FF00FF00"}},
          "modifiers":[],"slots":{},"eventBindings":{}},
        "alarm":{"id":"alarm","componentId":"draw/if","properties":{
          "condition":{"type":"state","variable":"shown"}},
          "modifiers":[],"slots":{"ops":["dot"]},"eventBindings":{}},
        "dot":{"id":"dot","componentId":"draw/rect","properties":{
          "xDp":{"type":"float","value":160},"yDp":{"type":"float","value":60},
          "widthDp":{"type":"float","value":10},"heightDp":{"type":"float","value":10},
          "color":{"type":"color","value":"#FF0000FF"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
      .trimIndent()
  }
}
