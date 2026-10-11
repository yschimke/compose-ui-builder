package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.CanvasZoomCommand
import ee.schimke.composeai.uibuilder.editor.MAX_CANVAS_ZOOM
import ee.schimke.composeai.uibuilder.editor.MIN_CANVAS_ZOOM
import ee.schimke.composeai.uibuilder.editor.canvasZoomAfter
import ee.schimke.composeai.uibuilder.editor.canvasZoomLabel
import ee.schimke.composeai.uibuilder.editor.canvasZoomStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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

  @Test
  fun `the zoom chords step from the scale the design is drawn at`() {
    // Framed at 62%, Ctrl/Cmd+= and Ctrl/Cmd+- land where the zoom bar's own buttons would.
    assertEquals(0.75f, canvasZoomAfter(CanvasZoomCommand.In, drawn = 0.62f))
    assertEquals(0.5f, canvasZoomAfter(CanvasZoomCommand.Out, drawn = 0.62f))
    assertEquals(MAX_CANVAS_ZOOM, canvasZoomAfter(CanvasZoomCommand.In, drawn = MAX_CANVAS_ZOOM))
    assertEquals(MIN_CANVAS_ZOOM, canvasZoomAfter(CanvasZoomCommand.Out, drawn = MIN_CANVAS_ZOOM))
  }

  @Test
  fun `fit unpins the zoom and actual size pins 100 percent from anywhere`() {
    assertNull(canvasZoomAfter(CanvasZoomCommand.Fit, drawn = 2f))
    assertNull(canvasZoomAfter(CanvasZoomCommand.Fit, drawn = 0.62f))
    assertEquals(1f, canvasZoomAfter(CanvasZoomCommand.ActualSize, drawn = 0.62f))
    assertEquals(1f, canvasZoomAfter(CanvasZoomCommand.ActualSize, drawn = 3f))
  }
}
