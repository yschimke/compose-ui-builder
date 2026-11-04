package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.CanvasHitBox
import ee.schimke.composeai.uibuilder.editor.CanvasHitTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * #354: a tap on a button whose runtime reports the label's box as the button's content area
 * selects the button first, and a second tap drills into the label.
 */
class CanvasHitTestTest {
  private val parents = mapOf("label" to "button", "button" to "column", "small" to "column")

  /** A 100×40 pill whose label box fills all but 2px of it, inside a large column. */
  private val pill =
    listOf(
      CanvasHitBox("column", 0f, 0f, 300f, 300f),
      CanvasHitBox("button", 0f, 0f, 100f, 40f),
      CanvasHitBox("label", 1f, 1f, 99f, 39f),
    )

  @Test
  fun `a tap on a label that fills its button selects the button`() {
    assertEquals("button", CanvasHitTest.tap(pill, 50f, 20f, null, parents))
    // Something else selected changes nothing.
    assertEquals("button", CanvasHitTest.tap(pill, 50f, 20f, "column", parents))
  }

  @Test
  fun `a second tap on the selected button drills into its label, and stays there`() {
    assertEquals("label", CanvasHitTest.tap(pill, 50f, 20f, "button", parents))
    assertEquals("label", CanvasHitTest.tap(pill, 50f, 20f, "label", parents))
  }

  @Test
  fun `a child that does not fill its container is selected directly, as before`() {
    val boxes =
      listOf(
        CanvasHitBox("column", 0f, 0f, 300f, 300f),
        CanvasHitBox("button", 0f, 0f, 100f, 40f),
        CanvasHitBox("label", 30f, 12f, 70f, 28f),
      )

    assertEquals("label", CanvasHitTest.tap(boxes, 50f, 20f, null, parents))
    // The button's padding, outside the label, is the button.
    assertEquals("button", CanvasHitTest.tap(boxes, 10f, 20f, null, parents))
    assertEquals("column", CanvasHitTest.tap(boxes, 200f, 200f, null, parents))
    assertNull(CanvasHitTest.tap(boxes, 400f, 400f, null, parents))
  }

  @Test
  fun `a press keeps the selection when it is one of the stacked boxes`() {
    assertEquals("button", CanvasHitTest.press(pill, 50f, 20f, null, parents))
    assertEquals("label", CanvasHitTest.press(pill, 50f, 20f, "label", parents))
    assertEquals("button", CanvasHitTest.press(pill, 50f, 20f, "column", parents))
  }

  @Test
  fun `siblings are never stacked, whatever their size`() {
    val boxes =
      listOf(CanvasHitBox("column", 0f, 0f, 100f, 100f), CanvasHitBox("small", 0f, 0f, 99f, 99f))
    val unrelated = mapOf("column" to "root", "small" to "root")

    assertEquals("small", CanvasHitTest.tap(boxes, 50f, 50f, null, unrelated))
  }
}
