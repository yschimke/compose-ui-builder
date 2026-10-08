package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.DesignPreviewPane
import ee.schimke.composeai.uibuilder.export.LauncherWidgetTemplates
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The resizable launcher pane in a live Preview: the handle drags the frame continuously, the
 * design re-lays out at the nearest cell count as the frame crosses into it, and letting go springs
 * the frame onto that count.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherWidgetResizablePaneTest {

  private val hello =
    LauncherWidgetTemplates.document(
      templateId = LauncherWidgetTemplates.HELLO_TEMPLATE,
      designId = "hello",
      catalogPin = JsonObject(mapOf("systemId" to JsonPrimitive("remote-widgets"))),
      environment = JsonObject(emptyMap()),
    )

  @Test
  fun `the handle resizes live and lands on whole cells`() =
    runDesktopComposeUiTest(width = 1400, height = 1000) {
      mainClock.autoAdvance = false
      setContent {
        MaterialTheme {
          Box(Modifier.size(1400.dp, 1000.dp).testTag("preview")) {
            DesignPreviewPane(
              hello,
              variants = emptyList(),
              modifier = Modifier.size(1400.dp, 1000.dp),
            )
          }
        }
      }
      var shot = 0
      fun capture(name: String) {
        val output =
          File(System.getProperty("uiBuilderProjectDir"), "build/launcher-resize-evidence").apply {
            mkdirs()
          }
        File(output, "${shot++}-$name.png")
          .writeBytes(
            checkNotNull(
                Image.makeFromBitmap(onNodeWithTag("preview").captureToImage().asSkiaBitmap())
                  .encodeToData(EncodedImageFormat.PNG)
              )
              .bytes
          )
      }
      fun label(size: String) = onNodeWithText("Resizable · $size", substring = true).assertExists()
      val handle = onNodeWithContentDescription("Resize widget")
      fun SemanticsNodeInteraction.centre(): Pair<Dp, Dp> =
        getBoundsInRoot().let { (it.left + it.right) / 2 to (it.top + it.bottom) / 2 }

      mainClock.advanceTimeBy(500)
      // The design's own size first: the hello widget is a 3x1.
      label("3x1")
      capture("3x1")
      val (startX, startY) = handle.centre()

      // Held a third of a cell right: the frame has moved with the pointer, the design has not.
      handle.performTouchInput {
        down(center)
        moveBy(Offset(10f, 0f))
        moveBy(Offset(width * 0f + 60f, 0f))
      }
      mainClock.advanceTimeBy(100)
      label("3x1")
      val (heldX, _) = handle.centre()
      assertTrue(heldX > startX, "the frame follows the handle while held: $startX → $heldX")
      capture("held-3x1")

      // On past half a cell and down a row: the design re-lays out at 4x2 while still held.
      handle.performTouchInput { moveBy(Offset(120f, 320f)) }
      mainClock.advanceTimeBy(100)
      label("4x2")
      capture("held-4x2")

      // Let go: the frame springs onto the whole cell count, a little short of where it was held.
      handle.performTouchInput { up() }
      mainClock.advanceTimeBy(80)
      capture("settling-4x2")
      mainClock.advanceTimeBy(1500)
      label("4x2")
      val (endX, endY) = handle.centre()
      assertTrue(endX > startX && endY > startY, "($startX, $startY) → ($endX, $endY)")
      capture("4x2")
    }
}
