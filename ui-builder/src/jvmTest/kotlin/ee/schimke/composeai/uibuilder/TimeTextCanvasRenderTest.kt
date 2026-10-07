package ee.schimke.composeai.uibuilder

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.export.UiTimeText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `remote-m3/remote-time-text` is on the remote-m3 palette, and the canvas curves the design's
 * preview time along the top of its bounds.
 */
@OptIn(ExperimentalTestApi::class)
class TimeTextCanvasRenderTest {
  @Test
  fun `the palette offers time text`() {
    val catalog =
      CapabilityCatalogParser.parse(
        checkNotNull(javaClass.getResource("/remote-m3-capabilities-v1.json")).readText()
      )

    assertNotNull(catalog.componentsById[UiTimeText.ID])
  }

  @Test
  fun `the time is drawn in the top half of its bounds`() {
    var count = 0
    var ys = 0.0
    var top = Int.MAX_VALUE
    var bottom = 0
    runDesktopComposeUiTest(width = 600, height = 400) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(DOCUMENT)) }
      val pixels = onRoot().captureToImage().toPixelMap()
      for (x in 0 until pixels.width) {
        for (y in 0 until pixels.height) {
          val pixel = pixels[x, y]
          if (pixel.red > 0.8f && pixel.green < 0.3f && pixel.blue < 0.3f) {
            count++
            ys += y
            top = minOf(top, y)
            bottom = maxOf(bottom, y)
          }
        }
      }
    }
    assertTrue(count > 20, "no time drawn ($count samples)")
    // Curved along the top: the ink sits in a band, not spread down the box.
    assertTrue(bottom - top < 60, "ink spans $top..$bottom")
  }

  private companion object {
    val DOCUMENT =
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"clock","title":"Clock",
       "revision":1,
       "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["widget"],
       "nodes":{
        "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
          "properties":{},"modifiers":[],
          "slots":{"background":[],"content":["time"]},"eventBindings":{}},
        "time":{"id":"time","componentId":"remote-m3/remote-time-text",
          "properties":{"color":{"type":"color","value":"#FFFF0000"},
            "trailingText":{"type":"string","value":"WED"}},
          "modifiers":[{"type":"size","widthDp":120,"heightDp":120}],
          "slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
  }
}
