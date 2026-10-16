package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.PropertyEditorControl
import ee.schimke.composeai.uibuilder.export.UiDrawing
import kotlin.test.Test
import kotlin.test.assertEquals
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
    val catalog = publishedTestCatalog("remote-m3")

    UiDrawing.COMPONENT_IDS.forEach { id ->
      assertNotNull(catalog.componentsById[id], "$id is not on the remote-m3 palette")
    }
    val ops = catalog.componentsById.getValue(UiDrawing.CANVAS).slots.single()
    assertTrue(UiDrawing.TRAIT in ops.acceptedTraits, ops.toString())
  }

  @Test
  fun `the inspector edits a canvas's viewport`() {
    val properties =
      publishedTestCatalog("remote-m3").componentsById.getValue(UiDrawing.CANVAS).propertiesByName

    for (side in listOf("viewportWidth", "viewportHeight")) {
      val editor = assertNotNull(properties.getValue(side).editor, side)
      assertEquals(PropertyEditorControl.NUMBER, editor.control, side)
      assertEquals(UiDrawing.Unit.VIEWPORT.minimum, editor.minimum, side)
    }
    assertEquals(
      PropertyEditorControl.ENUM,
      assertNotNull(properties.getValue("viewportMode").editor).control,
    )
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

  /**
   * Red samples (every second pixel each way, at density 2: one per dp²) of a 200 × 100 rectangle
   * filling a viewport of that size, on the 80dp canvas in [viewportMode].
   */
  private fun viewportRed(viewportMode: String): Int {
    var red = 0
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent {
        ProductionUiBuilderSurface(
          decodeProductionRendererDocument(VIEWPORT_DOCUMENT.replace("MODE", viewportMode))
        )
      }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width step 2) {
        for (y in 0 until pixels.height step 2) {
          if (pixels[x, y].isNear(Color.Red)) red++
        }
      }
    }
    return red
  }

  @Test
  fun `a canvas viewport scales its operations onto the canvas`() {
    // Stretched, the viewport's rectangle covers the 80 × 80 canvas; fitted, 80 × 40 of it.
    val stretched = viewportRed("stretch")
    val fitted = viewportRed("fit")
    assertTrue(stretched in 5_600..7_200, "stretched viewport drew $stretched red samples")
    assertTrue(fitted in 2_800..3_600, "fitted viewport drew $fitted red samples")
  }

  /** The rows red text covers, anchored at the canvas's middle in [verticalAlign]. */
  private fun textRows(verticalAlign: String): IntRange {
    var rows = IntRange.EMPTY
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent {
        ProductionUiBuilderSurface(
          decodeProductionRendererDocument(TEXT_DOCUMENT.replace("VALIGN", verticalAlign))
        )
      }
      val pixels = onRoot().captureToImage().toPixelMap()
      var top = Int.MAX_VALUE
      var bottom = Int.MIN_VALUE
      for (x in 0 until pixels.width) {
        for (y in 0 until pixels.height) {
          if (pixels[x, y].isNear(Color.Red)) {
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
          }
        }
      }
      rows = top..bottom
    }
    return rows
  }

  @Test
  fun `a text's vertical alignment puts that edge at the anchor`() {
    val hanging = textRows("top")
    val centred = textRows("center")
    val standing = textRows("bottom")
    assertTrue(!hanging.isEmpty() && !standing.isEmpty(), "no text drawn: $hanging, $standing")
    // Hung from the anchor the text starts where, stood on it, the text ends; centred between.
    assertTrue(hanging.first >= standing.last - 2, "top $hanging overlaps bottom $standing")
    assertTrue(
      centred.first in standing.first..hanging.first,
      "centre $centred is not between $standing and $hanging",
    )
  }

  /** Samples of [colour] on an 80dp canvas drawing [ops] (draw node JSON, `"id":{…}` pairs). */
  private fun countOn(colour: Color, opIds: List<String>, ops: String): Int {
    var count = 0
    val document =
      PAINT_DOCUMENT.replace("OP_IDS", opIds.joinToString(",") { "\"$it\"" }).replace("OPS", ops)
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(document)) }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width step 2) {
        for (y in 0 until pixels.height step 2) {
          if (pixels[x, y].isNear(colour)) count++
        }
      }
    }
    return count
  }

  @Test
  fun `a listed gradient runs through every colour it names`() {
    fun fill(colours: String) =
      """"fill":{"id":"fill","componentId":"draw/rect","properties":{
        "gradient":{"type":"enum","value":"horizontal"},$colours},
        "modifiers":[],"slots":{},"eventBindings":{}}"""
    // Red to blue alone never passes through pure green; red, green, blue does, at its middle.
    val twoEnds =
      countOn(
        Color.Green,
        listOf("fill"),
        fill(
          """"color":{"type":"color","value":"#FFFF0000"},
          "gradientColor":{"type":"color","value":"#FF0000FF"}"""
        ),
      )
    val listed =
      countOn(
        Color.Green,
        listOf("fill"),
        fill(""""gradientColors":{"type":"string","value":"#FF0000, #00FF00, #0000FF"}"""),
      )
    assertEquals(0, twoEnds, "a two-colour gradient drew green")
    assertTrue(listed > 20, "a three-colour gradient drew no green ($listed samples)")
  }

  @Test
  fun `a clear blend mode erases what is beneath it`() {
    val red =
      """"red":{"id":"red","componentId":"draw/rect","properties":{
        "color":{"type":"color","value":"#FFFF0000"}},"modifiers":[],"slots":{},"eventBindings":{}}"""
    val eraser =
      """"eraser":{"id":"eraser","componentId":"draw/rect","properties":{
        "blendMode":{"type":"enum","value":"clear"}},"modifiers":[],"slots":{},"eventBindings":{}}"""
    val drawn = countOn(Color.Red, listOf("red"), red)
    val erased = countOn(Color.Red, listOf("red", "eraser"), "$red,$eraser")
    assertTrue(drawn > 1_000, "the red rectangle was not drawn ($drawn samples)")
    assertEquals(0, erased, "a clear rectangle left red behind")
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

    /** An 80dp canvas drawing the operations `OPS`, in the order `OP_IDS`. */
    val PAINT_DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"paint","title":"Paint",
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
          "slots":{"ops":[OP_IDS]},"eventBindings":{}},
        OPS}}
      """
        .trimIndent()

    /** Red text anchored at the middle of an 80dp canvas, aligned vertically by `VALIGN`. */
    val TEXT_DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"text","title":"Text",
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
          "slots":{"ops":["label"]},"eventBindings":{}},
        "label":{"id":"label","componentId":"draw/text","properties":{
          "text":{"type":"string","value":"HHH"},
          "textSizeSp":{"type":"float","value":12},
          "verticalAlign":{"type":"enum","value":"VALIGN"},
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()

    /** An 80dp canvas drawing in a 200 × 100 viewport, filled by one red rectangle. */
    val VIEWPORT_DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"viewport","title":"Viewport",
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
        "canvas":{"id":"canvas","componentId":"draw/canvas","properties":{
          "viewportWidth":{"type":"float","value":200},
          "viewportHeight":{"type":"float","value":100},
          "viewportMode":{"type":"enum","value":"MODE"}},
          "modifiers":[{"type":"size","widthDp":80,"heightDp":80}],
          "slots":{"ops":["fill"]},"eventBindings":{}},
        "fill":{"id":"fill","componentId":"draw/rect","properties":{
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
