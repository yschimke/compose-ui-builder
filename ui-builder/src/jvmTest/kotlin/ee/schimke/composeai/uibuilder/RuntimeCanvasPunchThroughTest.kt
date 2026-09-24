package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json

/**
 * A runtime renderer's pixels live in a DOM layer *under* the editor, and reach the screen through
 * the `BlendMode.Clear` hole the renderer punches in the canvas frame. The hole has to survive to
 * the window: cleared inside an offscreen layer, it only emptied that layer's buffer, which was
 * then composited back over the workspace — every pinned-runtime design drew as a blank canvas
 * while its device previews, which sit above the editor, drew fine.
 */
@OptIn(ExperimentalTestApi::class)
class RuntimeCanvasPunchThroughTest {
  @Test
  fun `a runtime renderer's cleared frame reaches the window`() =
    runDesktopComposeUiTest(width = 1200, height = 800) {
      val root = File(System.getProperty("uiBuilderProjectDir"), "..")
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      val document = Json {
        ignoreUnknownKeys = true
      }
        .decodeFromString<UiBuilderDocument>(
          File(root, "experiments/remote-state-selection/bound-actions.uid").readText()
        )
      var hole: Rect? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            canvasRenderer = { _, _, _, _, _, _ ->
              // What CatalogRuntimeCanvas draws: nothing but the hole.
              Box(
                Modifier.fillMaxSize()
                  .drawWithContent {
                    drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                    drawContent()
                  }
                  .onGloballyPositioned { hole = it.boundsInWindow() }
              )
            },
          )
        }
      }
      waitForIdle()
      val bounds = assertNotNull(hole, "the runtime renderer was never placed")
      val pixels = onRoot().captureToImage().asSkiaBitmap()
      val x = bounds.center.x.roundToInt()
      val y = bounds.center.y.roundToInt()
      assertEquals(
        0,
        pixels.getColor(x, y) ushr 24,
        "the frame at ($x, $y) is painted over; the runtime layer beneath it cannot show",
      )
    }
}
