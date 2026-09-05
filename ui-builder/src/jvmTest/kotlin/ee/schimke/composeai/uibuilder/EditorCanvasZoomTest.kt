package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The zoom ladder, which is the part of the canvas's zoom that is not a pixel.
 *
 * The controls step from whatever the canvas is *drawn* at rather than from the last press, so
 * these cases are all "framed at some awkward scale, now zoom".
 */
class EditorCanvasZoomTest {
  @Test
  fun `zooming out of a framed design lands on the stop below it`() {
    assertEquals(0.5f, canvasZoomStep(0.62f, zoomIn = false))
    assertEquals(0.75f, canvasZoomStep(0.62f, zoomIn = true))
  }

  @Test
  fun `a scale that is already a stop moves off it`() {
    assertEquals(1.25f, canvasZoomStep(1f, zoomIn = true))
    assertEquals(0.75f, canvasZoomStep(1f, zoomIn = false))
  }

  @Test
  fun `the ladder stops at its ends`() {
    assertEquals(MAX_CANVAS_ZOOM, canvasZoomStep(MAX_CANVAS_ZOOM, zoomIn = true))
    assertEquals(MIN_CANVAS_ZOOM, canvasZoomStep(0.25f, zoomIn = false))
  }

  @Test
  fun `the readout says what the scale is and whether it is being framed`() {
    assertEquals("Fit · 62%", canvasZoomLabel(0.617f, fitting = true))
    assertEquals("125%", canvasZoomLabel(1.25f, fitting = false))
  }
}
