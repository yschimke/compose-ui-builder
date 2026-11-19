package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thin features of a watch face still draw when the face is drawn small
 * (yschimke/watchface-tools#27 item 4): a 1px stroke, and indices 1px or less wide, which at half
 * scale are half a pixel.
 */
class WatchFaceHairlineTest {
  private val face =
    wffFace(
      listOf("ring", "legacy", "thin", "one", "frac", "none"),
      listOf(
          wffNode(
            "ring",
            "wff/part-draw",
            mapOf("x" to 0, "y" to 0, "width" to 450, "height" to 450, "shape" to "none"),
            mapOf("content" to listOf("ring-shape")),
          ),
          wffNode(
            "ring-shape",
            "wff/shape",
            mapOf("shape" to "ellipse", "x" to 25, "y" to 25, "width" to 400, "height" to 400),
            mapOf("content" to listOf("ring-stroke")),
          ),
          wffNode("ring-stroke", "wff/stroke", mapOf("color" to "#ffffffff", "thickness" to 1)),
          wffNode(
            "legacy",
            "wff/part-draw",
            mapOf(
              "x" to 75,
              "y" to 75,
              "width" to 300,
              "height" to 300,
              "shape" to "ellipse",
              "strokeColor" to "#ffffffff",
              "strokeWidth" to 1,
            ),
          ),
          bar("thin", 180, 1.6),
          bar("one", 260.5, 1),
          bar("frac", 300.3, 0.8),
          bar("none", 340, 0),
        )
        .joinToString(","),
    )

  private fun bar(id: String, x: Number, width: Number) =
    wffNode(
      id,
      "wff/part-draw",
      mapOf(
        "x" to x,
        "y" to 100,
        "width" to width,
        "height" to 250,
        "shape" to "rectangle",
        "fillColor" to "#ffffffff",
      ),
    )

  private fun row(side: Int, x: Float): List<Int> {
    val bitmap = renderWffFaceAt(face, side, "hairline-$side")
    val k = side * 2 / 450f
    val cx = (x * k).toInt()
    return (cx - 2..cx + 2).map { bitmap.argb(it, (225f * k).toInt())[1] }
  }

  /** The brightest pixel near face x [x] on the centre row of a face drawn [side]dp wide. */
  private fun brightest(side: Int, x: Float): Int = row(side, x).max()

  /** How much of a pixel is lit near face x [x], summed across the pixels it straddles. */
  private fun coverage(side: Int, x: Float): Int = row(side, x).sum()

  @Test
  fun `indices a pixel or less wide still draw on a face at half scale`() {
    // 112dp at 2px to the dp: one face pixel is ≈0.5 canvas pixels.
    for ((name, x) in listOf("1.6px" to 180.8f, "1px" to 261f, "0.8px" to 300.7f)) assertEquals(
      255,
      brightest(112, x),
      name,
    )
  }

  @Test
  fun `a part with no width draws nothing`() {
    assertEquals(0, brightest(112, 340f))
  }

  @Test
  fun `a 1px stroke is drawn at least a pixel wide on a face at half scale`() {
    // The wff/shape ring on its left edge, and the legacy part's: a whole pixel's worth, though
    // a stroke at a fractional position shares it between two. Before, about half of one.
    assertTrue(coverage(112, 25.5f) >= 230, "shape stroke ${row(112, 25.5f)}")
    assertTrue(coverage(112, 75.5f) >= 230, "legacy stroke ${row(112, 75.5f)}")
  }

  @Test
  fun `at full scale a thin index keeps its own width`() {
    // 227dp: a face pixel is ≈1 canvas pixel, so the 1px index is one pixel, not widened.
    val bitmap = renderWffFaceAt(face, 227)
    val y = (225 * 454 / 450f).toInt()
    val lit = (255..270).count { bitmap.argb(it, y)[1] > 0 }
    assertEquals(1, lit)
  }
}
