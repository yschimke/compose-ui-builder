package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import ee.schimke.composeai.uibuilder.canvas.LocalWearRowTransformation
import ee.schimke.composeai.uibuilder.canvas.WearCanvasButton
import ee.schimke.composeai.uibuilder.canvas.WearCanvasButtonGroup
import ee.schimke.composeai.uibuilder.canvas.WearCanvasCard
import ee.schimke.composeai.uibuilder.canvas.WearCanvasListHeader
import ee.schimke.composeai.uibuilder.canvas.WearCanvasTransformingLazyColumn
import ee.schimke.composeai.uibuilder.canvas.WearRowTransformation
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

  /** Reports how many components apply [row] at draw time, which is when the list reads it. */
  @androidx.compose.runtime.Composable
  private fun AppliedWhileDrawn(row: WearRowTransformation, onDraw: (Int) -> Unit) {
    Spacer(Modifier.fillMaxSize().drawBehind { onDraw(row.appliedBy) })
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
      CompositionLocalProvider(
        LocalWearRowTransformation provides WearRowTransformation(recorded)
      ) {
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
      CompositionLocalProvider(
        LocalWearRowTransformation provides WearRowTransformation(recorded)
      ) {
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
   * A list's last row is often a `ButtonGroup`, and upstream is explicit that the group takes the
   * transformation and its buttons do not: applied to both, each button is scaled about its own
   * centre inside a group that is not, which is how that row drew wrong on the canvas.
   */
  @Test
  fun `a button group takes the transformation and hides it from its buttons`() {
    val recorded = RecordingTransformation()
    val row = WearRowTransformation(recorded)
    var seenByButton: WearRowTransformation? = row
    var applied = -1
    render {
      AppliedWhileDrawn(row) { applied = it }
      CompositionLocalProvider(LocalWearRowTransformation provides row) {
        WearCanvasButtonGroup(weights = listOf(null), modifier = Modifier.fillMaxSize()) { _, next
          ->
          seenByButton = LocalWearRowTransformation.current
          WearCanvasButton(variant = "filled", enabled = true, modifier = next) { Text("Go") }
        }
      }
    }

    assertTrue(recorded.containerApplications > 0, "the group ignored the transformation")
    assertEquals(null, seenByButton, "the group's buttons were handed the row's transformation too")
    assertEquals(1, applied, "only the group applies it")
  }

  /** A list header takes it too, rather than getting shorter at the top while drawing full size. */
  @Test
  fun `a list header applies the transformation it is given`() {
    val recorded = RecordingTransformation()
    val row = WearRowTransformation(recorded)
    var applied = -1
    render {
      AppliedWhileDrawn(row) { applied = it }
      CompositionLocalProvider(LocalWearRowTransformation provides row) {
        WearCanvasListHeader(text = "Header", modifier = Modifier.fillMaxSize())
      }
    }

    assertTrue(recorded.containerApplications > 0, "the header ignored the transformation")
    assertEquals(1, applied)
  }

  /**
   * A row whose component takes no transformation is left to the list, which transforms it whole:
   * nothing claims it, so the list's own layer does.
   */
  @Test
  fun `a plain text row leaves the transformation to the list`() {
    val row = WearRowTransformation(RecordingTransformation())
    var applied = -1
    render {
      AppliedWhileDrawn(row) { applied = it }
      CompositionLocalProvider(LocalWearRowTransformation provides row) { Text("Row") }
    }

    assertEquals(0, applied)
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
        handed = LocalWearRowTransformation.current?.surface
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
      handed = LocalWearRowTransformation.current?.surface
      WearCanvasCard(variant = "title", modifier = Modifier.fillMaxSize()) { Text("Row") }
    }

    assertEquals(null, handed)
  }
}
