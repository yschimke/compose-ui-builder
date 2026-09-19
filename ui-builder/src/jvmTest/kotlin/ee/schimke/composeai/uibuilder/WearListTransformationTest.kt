package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The canvas's Wear list hands its rows the **whole** transformation, not half of it.
 *
 * `TransformingLazyColumn` applies a row's transformation in two halves:
 * `Modifier.transformedHeight` (the layout half — the row gets shorter) and `SurfaceTransformation`
 * (the drawing half — the scale and the fade, which the *component* applies to itself). The canvas
 * had exactly one: it passed the first and not the second, so a Wear list drew as a plain column of
 * full-size, full-brightness rows while the generated screen beside it scaled and faded. That is
 * what somebody reported from the editor.
 *
 * ## Why this asserts calls rather than pixels
 *
 * The transformation is **animated** — the spec settles as frames advance — so a still render shows
 * every row at full scale and cannot tell the two halves apart. Two attempts at a pixel test proved
 * it: with the transformation wired and with it removed, the measured widths differed by the same
 * 4% (the rounded corner), which would have been a green test guarding nothing.
 *
 * So this asks the question directly, in two halves that together cover the chain:
 *
 * - the **list** provides a transformation to its rows (the local is not null inside an item), and
 * - a **component** applies the transformation it is given — observed through a recording
 *   implementation of the interface, whose `apply*` methods the library calls while drawing, so
 *   they are called **if and only if** the component passed it on.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class WearListTransformationTest {

  private class RecordingTransformation : SurfaceTransformation {
    var containerApplications = 0

    override fun createContainerPainter(
      painter: Painter,
      shape: Shape,
      border: BorderStroke?,
    ): Painter = painter

    override fun GraphicsLayerScope.applyContainerTransformation() {
      containerApplications += 1
    }

    override fun GraphicsLayerScope.applyContentTransformation() = Unit
  }

  /** Renders [content] in a settled scene, so anything the draw path applies has been applied. */
  private fun render(content: @androidx.compose.runtime.Composable () -> Unit) {
    val scene =
      ImageComposeScene(width = 384, height = 384, density = Density(2f)) {
        MaterialTheme { content() }
      }
    try {
      var nanos = 0L
      repeat(5) {
        nanos += 16_666_667L
        scene.render(nanos)
      }
    } finally {
      scene.close()
    }
  }

  @Test
  fun `a card applies the transformation it is given`() {
    val recorded = RecordingTransformation()
    render {
      CompositionLocalProvider(LocalWearSurfaceTransformation provides recorded) {
        WearCanvasCard(variant = "title", modifier = Modifier.fillMaxSize()) { Text("Row") }
      }
    }

    assertTrue(
      recorded.containerApplications > 0,
      "the card ignored the transformation it was handed: it is passing `transformedHeight` and " +
        "not `SurfaceTransformation`, so rows do not scale or fade",
    )
  }

  @Test
  fun `a button applies the transformation it is given`() {
    val recorded = RecordingTransformation()
    render {
      CompositionLocalProvider(LocalWearSurfaceTransformation provides recorded) {
        WearCanvasButton(
          variant = "filled",
          enabled = true,
          modifier = Modifier.fillMaxSize(),
        ) {
          Text("Go")
        }
      }
    }

    assertTrue(recorded.containerApplications > 0, "the button ignored the transformation")
  }

  /**
   * The half the list owns: a row composed inside the canvas's Wear list is handed the real
   * transformation, so the component above has something to apply.
   */
  @Test
  fun `the list hands its rows a transformation`() {
    var handed: SurfaceTransformation? = null
    render {
      WearCanvasTransformingLazyColumn(
        itemCount = 3,
        verticalSpacingDp = 4f,
        modifier = Modifier.fillMaxSize(),
      ) { _, _ ->
        handed = LocalWearSurfaceTransformation.current
        Text("Row")
      }
    }

    assertNotNull(
      handed,
      "the canvas's Wear list handed its rows no transformation, so nothing inside one can scale " +
        "or fade",
    )
  }

  /**
   * Outside a list there is none, and nothing fabricates one: the library's own default is what a
   * card that is not in a `TransformingLazyColumn` should use.
   */
  @Test
  fun `a card outside a list is handed no transformation`() {
    var handed: SurfaceTransformation? = null
    render {
      handed = LocalWearSurfaceTransformation.current
      WearCanvasCard(variant = "title", modifier = Modifier.fillMaxSize()) { Text("Row") }
    }

    assertEquals(null, handed)
  }
}
