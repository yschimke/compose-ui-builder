package ee.schimke.composeai.uibuilder

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import ee.schimke.composeai.uibuilder.editor.SpacingGuide
import ee.schimke.composeai.uibuilder.editor.SpacingSegment
import ee.schimke.composeai.uibuilder.editor.formatSpacingDp
import ee.schimke.composeai.uibuilder.editor.spacingMeasurement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpacingMeasurementTest {
  @Test
  fun `side by side measures the horizontal gap through the shared span`() {
    val m =
      spacingMeasurement(selected = Rect(0f, 0f, 40f, 40f), hovered = Rect(56f, 10f, 100f, 60f))
    // Shared rows 10..40, so the line runs at 25.
    assertEquals(listOf(SpacingSegment(Offset(40f, 25f), Offset(56f, 25f), 16f)), m.segments)
    assertTrue(m.guides.isEmpty())
  }

  @Test
  fun `stacked measures the vertical gap whichever is on top`() {
    val below = spacingMeasurement(Rect(0f, 0f, 100f, 20f), Rect(0f, 28f, 100f, 48f))
    assertEquals(listOf(SpacingSegment(Offset(50f, 20f), Offset(50f, 28f), 8f)), below.segments)
    val above = spacingMeasurement(Rect(0f, 28f, 100f, 48f), Rect(0f, 0f, 100f, 20f))
    assertEquals(listOf(SpacingSegment(Offset(50f, 20f), Offset(50f, 28f), 8f)), above.segments)
  }

  @Test
  fun `diagonal measures both gaps from the selection's centre with guides to the hovered node`() {
    val m = spacingMeasurement(Rect(0f, 0f, 20f, 20f), Rect(30f, 50f, 60f, 80f))
    assertEquals(
      listOf(
        SpacingSegment(Offset(20f, 10f), Offset(30f, 10f), 10f),
        SpacingSegment(Offset(10f, 20f), Offset(10f, 50f), 30f),
      ),
      m.segments,
    )
    assertEquals(
      listOf(
        // Up the hovered node's left edge to the horizontal line's height…
        SpacingGuide(Offset(30f, 50f), Offset(30f, 10f)),
        // …and along its top edge to the vertical line's column.
        SpacingGuide(Offset(30f, 50f), Offset(10f, 50f)),
      ),
      m.guides,
    )
  }

  @Test
  fun `hovering the parent measures the four insets through the selection's centre`() {
    val m =
      spacingMeasurement(selected = Rect(16f, 8f, 84f, 32f), hovered = Rect(0f, 0f, 100f, 48f))
    assertEquals(
      listOf(
        SpacingSegment(Offset(0f, 20f), Offset(16f, 20f), 16f),
        SpacingSegment(Offset(84f, 20f), Offset(100f, 20f), 16f),
        SpacingSegment(Offset(50f, 0f), Offset(50f, 8f), 8f),
        SpacingSegment(Offset(50f, 32f), Offset(50f, 48f), 16f),
      ),
      m.segments,
    )
  }

  @Test
  fun `hovering a child measures it inside the selection and skips shared edges`() {
    // Flush to the left and top of the selection: only right and bottom remain.
    val m =
      spacingMeasurement(selected = Rect(0f, 0f, 100f, 100f), hovered = Rect(0f, 0f, 60f, 76f))
    assertEquals(listOf(24f, 40f), m.segments.map { it.dp }.sorted())
  }

  @Test
  fun `overlapping boxes measure their matching edges`() {
    val m = spacingMeasurement(Rect(0f, 0f, 50f, 50f), Rect(20f, 10f, 70f, 50f))
    // Left edges 20 apart, right edges 20, top edges 10; the bottoms are shared.
    assertEquals(listOf(10f, 20f, 20f), m.segments.map { it.dp }.sorted())
  }

  @Test
  fun `the same box, or an empty one, measures nothing`() {
    val box = Rect(0f, 0f, 10f, 10f)
    assertTrue(spacingMeasurement(box, box).segments.isEmpty())
    assertTrue(spacingMeasurement(box, Rect(20f, 0f, 20f, 10f)).segments.isEmpty())
    // Bounds converted back from pixels are not exact; a hair's difference is still the same box.
    assertTrue(spacingMeasurement(box, Rect(0.01f, 0f, 10f, 10.02f)).segments.isEmpty())
  }

  @Test
  fun `touching boxes have no gap to label`() {
    assertTrue(
      spacingMeasurement(Rect(0f, 0f, 10f, 10f), Rect(10f, 0f, 20f, 10f)).segments.isEmpty()
    )
  }

  @Test
  fun `labels read to a tenth without a trailing zero`() {
    assertEquals("16", formatSpacingDp(16f))
    assertEquals("16", formatSpacingDp(15.999f))
    assertEquals("12.5", formatSpacingDp(12.5f))
    assertEquals("0.3", formatSpacingDp(0.26f))
  }
}
