package ee.schimke.composeai.uibuilder

import androidx.compose.ui.geometry.Rect
import ee.schimke.composeai.uibuilder.reference.RasterRect
import ee.schimke.composeai.uibuilder.reference.ReferenceLayer
import ee.schimke.composeai.uibuilder.reference.ReferenceMatchSource
import ee.schimke.composeai.uibuilder.reference.ReferenceMeasureFrame
import ee.schimke.composeai.uibuilder.reference.ReferenceRaster
import ee.schimke.composeai.uibuilder.reference.alignmentFor
import ee.schimke.composeai.uibuilder.reference.diffRasters
import ee.schimke.composeai.uibuilder.reference.inkBox
import ee.schimke.composeai.uibuilder.reference.matchLayer
import ee.schimke.composeai.uibuilder.reference.matchPatch
import ee.schimke.composeai.uibuilder.reference.resampleOnto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The measuring half, on pictures built from rectangles: a "label" is a few dark bars of different
 * lengths, which is enough texture to be found and is exactly what text looks like to a matcher.
 */
class ReferenceMatchingTest {
  private val white = 0xFFFFFFFF.toInt()
  private val ink = 0xFF202020.toInt()

  /** A 200 × 120 white picture with [draw] applied. */
  private fun picture(draw: IntArray.(Int) -> Unit = {}): ReferenceRaster {
    val width = 200
    val pixels = IntArray(width * 120) { white }
    pixels.draw(width)
    return ReferenceRaster(width, 120, pixels)
  }

  private fun IntArray.box(width: Int, left: Int, top: Int, right: Int, bottom: Int) {
    for (y in top until bottom) for (x in left until right) this[y * width + x] = ink
  }

  /** A fake two-word label whose glyph height is [size], at [left], [top]. */
  private fun IntArray.label(width: Int, left: Int, top: Int, size: Int) {
    val unit = maxOf(1, size / 4)
    box(width, left, top, left + unit * 3, top + size)
    box(width, left + unit * 4, top + size / 3, left + unit * 9, top + size)
    box(width, left + unit * 10, top, left + unit * 11, top + size)
    box(width, left + unit * 13, top + size / 2, left + unit * 18, top + size)
  }

  @Test
  fun `identical pictures do not differ`() {
    val a = picture { label(it, 20, 20, 16) }
    val report = diffRasters(a, a, cellPx = 8)
    assertEquals(0f, report.mismatch)
    assertTrue(report.regions.isEmpty())
    assertEquals(1f, report.coverage)
  }

  @Test
  fun `a moved block is one region where it was and where it went`() {
    val design = picture { box(it, 20, 20, 60, 40) }
    val reference = picture { box(it, 120, 70, 160, 90) }
    val report = diffRasters(design, reference, cellPx = 8)
    assertEquals(2, report.regions.size)
    assertTrue(
      report.regions.any { it.rect.left <= 20 && it.rect.right >= 60 && it.rect.top <= 20 }
    )
    assertTrue(report.regions.any { it.rect.left <= 120 && it.rect.bottom >= 90 })
  }

  @Test
  fun `only the placed reference is compared, and transparency there reads as white`() {
    val design = picture { box(it, 0, 0, 100, 120) }
    val transparent = ReferenceRaster(200, 120, IntArray(200 * 120))
    // Placed over the right half only: the dark left half of the design is not compared at all,
    // and the transparent right half agrees with the design's white.
    val report =
      diffRasters(design, transparent, cellPx = 8, coverage = RasterRect(100, 0, 200, 120))
    assertEquals(0.5f, report.coverage)
    assertEquals(0f, report.mismatch)
    // Placed over all of it, the dark half is a difference against the transparent white.
    assertEquals(0.5f, diffRasters(design, transparent, cellPx = 8).mismatch)
  }

  @Test
  fun `a transparent snapshot still matches the design it was taken of`() {
    val design = picture { label(it, 40, 40, 16) }
    val snapshot =
      ReferenceRaster(200, 120, IntArray(200 * 120) { if (design.pixels[it] == white) 0 else ink })
    val match =
      assertNotNull(matchPatch(design, snapshot, RasterRect(40, 40, 112, 56), searchPx = 8))
    assertEquals(0, match.dx)
    assertEquals(0, match.dy)
    assertEquals(0f, match.score)
  }

  @Test
  fun `the ink box is the drawing, not the window`() {
    val raster = picture { label(it, 30, 40, 12) }
    assertEquals(RasterRect(30, 40, 30 + 3 * 18, 52), inkBox(raster, RasterRect(10, 20, 150, 80)))
    assertNull(inkBox(raster, RasterRect(150, 90, 199, 119)))
  }

  @Test
  fun `a shifted label is found where it went`() {
    val design = picture { label(it, 40, 40, 16) }
    val reference = picture { label(it, 46, 43, 16) }
    val match =
      assertNotNull(
        matchPatch(design, reference, RasterRect(40, 40, 40 + 4 * 18, 56), searchPx = 12)
      )
    assertEquals(6, match.dx)
    assertEquals(3, match.dy)
    assertEquals(1f, match.scale)
    assertTrue(match.confident)
  }

  @Test
  fun `a flat patch is refused rather than matched anywhere`() {
    val flat = picture()
    assertNull(matchPatch(flat, flat, RasterRect(10, 10, 60, 40), searchPx = 8))
  }

  @Test
  fun `a bigger label in the reference reads as a bigger font`() {
    // 16 px glyphs on the design, 20 in the reference: a 25% larger type size.
    val design = picture { label(it, 20, 30, 16) }
    val reference = picture { label(it, 20, 30, 20) }
    val frame = ReferenceMeasureFrame(design, reference, samplesPerDp = 1f)
    val match =
      assertNotNull(
        matchLayer(
          frame,
          ReferenceLayer("title", Rect(16f, 26f, 120f, 50f), text = true),
          boxMarks = emptyList(),
          layoutBoxes = emptyList(),
        )
      )
    assertEquals(ReferenceMatchSource.Pixels, match.source)
    assertTrue(match.scale in 1.2f..1.3f, "scale ${match.scale}")
    val alignment = alignmentFor(match, currentFontSizeSp = 16f)
    assertEquals(20f, alignment.fontSizeSp)
    // The ink sits a little inside the box and moves with the type; at most a dp of correction.
    assertTrue(kotlin.math.abs(alignment.moveXDp) <= 1, "moveX ${alignment.moveXDp}")
  }

  @Test
  fun `a box the operator drew wins, and for text it is narrowed to the ink inside`() {
    val design = picture { label(it, 20, 30, 12) }
    val reference = picture { label(it, 32, 50, 12) }
    val frame = ReferenceMeasureFrame(design, reference, samplesPerDp = 1f)
    val match =
      assertNotNull(
        matchLayer(
          frame,
          ReferenceLayer("title", Rect(16f, 26f, 100f, 46f), text = true),
          // A generous box round the label in the reference, overlapping the layer.
          boxMarks = listOf(Rect(26f, 40f, 110f, 70f)),
          layoutBoxes = emptyList(),
        )
      )
    assertEquals(ReferenceMatchSource.BoxMark, match.source)
    val alignment = alignmentFor(match, currentFontSizeSp = 14f)
    assertEquals(12, alignment.moveXDp)
    assertEquals(20, alignment.moveYDp)
    assertNull(alignment.fontSizeSp)
  }

  @Test
  fun `an svg layout box sizes a container`() {
    val frame = ReferenceMeasureFrame(picture(), picture(), samplesPerDp = 1f)
    val match =
      assertNotNull(
        matchLayer(
          frame,
          ReferenceLayer("card", Rect(10f, 10f, 110f, 60f), text = false),
          boxMarks = emptyList(),
          layoutBoxes = listOf(Rect(14f, 10f, 124f, 62f)),
        )
      )
    assertEquals(ReferenceMatchSource.LayoutBox, match.source)
    val alignment = alignmentFor(match, currentFontSizeSp = null)
    assertEquals(4, alignment.moveXDp)
    assertEquals(0, alignment.moveYDp)
    assertEquals(110, alignment.widthDp)
    assertEquals(52, alignment.heightDp)
  }

  @Test
  fun `without pixel search a match needs a box`() {
    val design = picture { label(it, 40, 40, 16) }
    val frame = ReferenceMeasureFrame(design, design, samplesPerDp = 1f)
    assertNull(
      matchLayer(
        frame,
        ReferenceLayer("title", Rect(36f, 36f, 120f, 60f), text = true),
        boxMarks = emptyList(),
        layoutBoxes = emptyList(),
        pixelSearch = false,
      )
    )
  }

  @Test
  fun `resampling a 3x picture onto a dp grid keeps where things are`() {
    val big = ReferenceRaster(600, 360, IntArray(600 * 360) { white })
    for (y in 90 until 150) for (x in 60 until 180) big.pixels[y * 600 + x] = ink
    val grid = resampleOnto(big, 200, 120, 0f, 0f, 200f, 120f)
    assertEquals(RasterRect(20, 30, 60, 50), inkBox(grid, RasterRect(0, 0, 200, 120)))
  }
}
