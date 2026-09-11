package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import generated.uibuilder.ClickableStateLayout
import java.io.File
import kotlin.math.abs
import kotlin.test.*
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/** Runs the source whose exact bytes are checked by the shared export and hosted MCP tests. */
@OptIn(ExperimentalTestApi::class)
class GeneratedLayoutClicksTest {
  @Test
  fun `compiled layout clicks select both cases and fallback at density one`() = checkClicks(1f)

  @Test
  fun `compiled layout clicks select both cases and fallback at density two`() = checkClicks(2f)

  private fun checkClicks(density: Float) =
    runDesktopComposeUiTest(width = 900, height = 900) {
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(density)) {
          MaterialTheme { Box(Modifier.size(360.dp).testTag("frame")) { ClickableStateLayout() } }
        }
      }
      val frame = onNodeWithTag("frame")
      var capture = 0
      fun color(expected: Color) {
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val screenshot = frame.captureToImage()
        val pixels = screenshot.toPixelMap()
        val output =
          File(System.getProperty("uiBuilderProjectDir"), "build/layout-click-evidence").apply {
            mkdirs()
          }
        File(output, "density-${density.toInt()}-${capture++}.png")
          .writeBytes(
            checkNotNull(
                Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
              )
              .bytes
          )
        val actual = pixels[pixels.width / 2, pixels.height / 2]
        assertTrue(
          abs(actual.red - expected.red) < .03f &&
            abs(actual.green - expected.green) < .03f &&
            abs(actual.blue - expected.blue) < .03f,
          "expected $expected, got $actual",
        )
      }
      color(Color(0xff6750a4))
      assertEquals(312f * density, onNode(hasClickAction()).fetchSemanticsNode().boundsInRoot.width)
      onNode(hasClickAction()).performTouchInput { click() }
      color(Color(0xff008577))
      onNode(hasClickAction()).performTouchInput { click() }
      color(Color(0xff3949ab))
      onNode(hasClickAction()).performTouchInput { click() }
      color(Color(0xff6750a4))
    }
}
