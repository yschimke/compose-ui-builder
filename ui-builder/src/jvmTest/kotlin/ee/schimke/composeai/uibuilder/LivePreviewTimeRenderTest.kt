package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.renderer.sdk.withTimeRunning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/**
 * A design that reads the clock holds still at its `fixedTime` until a surface lets time run, and
 * then follows the wall clock frame by frame — fractions of a second included, which is what makes
 * a `time.continuousSecond` sweep smooth rather than a once-a-second jump.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LivePreviewTimeRenderTest {
  private val document = decodeProductionRendererDocument(DOCUMENT)

  // A scene rather than a compose rule: a running clock asks for a frame every frame, so a rule's
  // wait for idle would wait for ever. Each `render` is one frame, at the wall clock it ran at.
  private fun barLeftEdges(document: UiBuilderDocument, samples: Int): List<Int> {
    val scene = ImageComposeScene(300, 200, Density(1f)) { ProductionUiBuilderSurface(document) }
    try {
      return (0 until samples).map { frame ->
        Thread.sleep(250)
        val bitmap = Bitmap().apply { allocN32Pixels(300, 200) }
        check(scene.render(frame * 250_000_000L).readPixels(bitmap))
        (0 until 300).firstOrNull { x -> bitmap.getColor(x, 10) == RED } ?: -1
      }
    } finally {
      scene.close()
    }
  }

  @Test
  fun `a settled design draws its fixed time and holds it`() {
    // `fixedTime` is 10:10:30.5: half a second into the minute, so the bar sits at 50dp.
    assertEquals(listOf(50, 50, 50), barLeftEdges(document, samples = 3))
  }

  @Test
  fun `a running design follows the wall clock within the second`() {
    // The bar wraps once a second, so samples a quarter-second apart can only all match if time is
    // not running — and only change by whole seconds if the fraction were dropped.
    val edges = barLeftEdges(document.withTimeRunning(true), samples = 4)
    assertTrue(edges.all { it in 0..100 }, "the bar left the second it is drawn in: $edges")
    assertTrue(edges.distinct().size > 1, "a running design held still: $edges")
  }

  private companion object {
    const val RED = 0xFFFF0000.toInt()

    /** A red bar whose left edge is the fraction of the current second, times 100dp. */
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"clock-bar","title":"Clock bar",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":300,"heightDp":200,"density":1.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr",
         "fixedTime":"2024-05-16T10:10:30.500Z","animations":"settled"},
       "stateVariables":{},"roots":["canvas"],
       "nodes":{
        "canvas":{"id":"canvas","componentId":"draw/canvas","properties":{},
          "modifiers":[{"type":"size","widthDp":300,"heightDp":200}],
          "slots":{"ops":["bar"]},"eventBindings":{}},
        "bar":{"id":"bar","componentId":"draw/rect","properties":{
          "xDp":{"type":"expr","op":"mul","args":[
            {"type":"expr","op":"mod","args":[
              {"type":"system","value":"time.continuousSecond"},{"type":"int","value":1}]},
            {"type":"int","value":100}]},
          "yDp":{"type":"float","value":0},
          "widthDp":{"type":"float","value":20},"heightDp":{"type":"float","value":200},
          "color":{"type":"color","value":"#FFFF0000"}},
          "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
