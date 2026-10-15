package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * `remote-m3/remote-curved-progress-indicator` is drawn as the determinate arc the export and the
 * device preview draw, at its authored progress, rather than Wear's indeterminate
 * `ArcProgressIndicator`, which a one-frame PNG export drew as nothing.
 */
@OptIn(ExperimentalTestApi::class)
class CurvedProgressRenderTest {
  @Test
  fun `a full curved indicator draws its whole sweep in the indicator colour`() =
    runDesktopComposeUiTest(width = 432, height = 248) {
      setContent { ProductionUiBuilderSurface(decodeProductionRendererDocument(document(1.0f))) }
      waitForIdle()
      val full = redSamples()
      assertTrue(full > 400, "a full ring drew too little indicator ($full red samples)")
    }

  @Test
  fun `the indicator covers its progress, so half draws less than full and none draws nothing`() =
    runDesktopComposeUiTest(width = 432, height = 248) {
      var progress by mutableStateOf(1.0f)
      setContent {
        ProductionUiBuilderSurface(decodeProductionRendererDocument(document(progress)))
      }
      waitForIdle()
      val full = redSamples()
      progress = 0.5f
      waitForIdle()
      val half = redSamples()
      progress = 0f
      waitForIdle()
      val none = redSamples()

      assertTrue(half in (full / 4)..(full * 3 / 4), "half drew $half of $full")
      assertTrue(none < 20, "no progress still drew $none red samples")
    }

  @Test
  fun `the track stops gapAngleDegrees short of the indicator`() =
    runDesktopComposeUiTest(width = 432, height = 248) {
      var gap by mutableStateOf(0f)
      setContent {
        ProductionUiBuilderSurface(decodeProductionRendererDocument(document(0.5f, gap)))
      }
      waitForIdle()
      val flush = samples(Color.Blue)
      gap = 90f
      waitForIdle()
      val gapped = samples(Color.Blue)

      assertTrue(gapped < flush * 3 / 4, "a 90° gap left $gapped of $flush track samples")
    }

  private fun androidx.compose.ui.test.ComposeUiTest.redSamples(): Int = samples(Color.Red)

  private fun androidx.compose.ui.test.ComposeUiTest.samples(color: Color): Int {
    val pixels = onRoot().captureToImage().toPixelMap()
    var count = 0
    for (x in 0 until pixels.width) for (y in 0 until pixels.height) {
      if (pixels[x, y].isNear(color)) count++
    }
    return count
  }

  private fun Color.isNear(other: Color): Boolean =
    abs(red - other.red) < 0.1f && abs(green - other.green) < 0.1f && abs(blue - other.blue) < 0.1f

  private fun document(progress: Float, gap: Float = 2f) =
    """
    {"schema":"compose-ui-builder-document/v1-candidate","id":"curved","title":"Curved",
     "revision":1,
     "catalogPin":{"systemId":"remote-m3","catalogRevision":"candidate",
       "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
     "environment":{"widthDp":216,"heightDp":124,"density":2.0,"theme":"dark",
       "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
     "stateVariables":{},"roots":["widget"],
     "nodes":{
      "widget":{"id":"widget","componentId":"remote-m3/widget-container-large",
        "properties":{},"modifiers":[],
        "slots":{"background":[],"content":["ring"]},"eventBindings":{}},
      "ring":{"id":"ring","componentId":"remote-m3/remote-curved-progress-indicator",
        "properties":{
          "progress":{"type":"float","value":$progress},
          "startAngle":{"type":"float","value":-90},
          "sweepAngle":{"type":"float","value":360},
          "gapAngleDegrees":{"type":"float","value":$gap},
          "indicatorColor":{"type":"color","value":"#FFFF0000"},
          "trackColor":{"type":"color","value":"#FF0000FF"}},
        "modifiers":[{"type":"size","widthDp":80,"heightDp":80}],
        "slots":{},"eventBindings":{}}}}
    """
      .trimIndent()
}
