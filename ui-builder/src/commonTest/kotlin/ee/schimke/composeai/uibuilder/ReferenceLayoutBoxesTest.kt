package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReferenceLayoutBoxesTest {
  @Test
  fun `rects become fractions of the viewBox`() {
    val boxes =
      extractSvgLayoutBoxes(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 200">
             <rect data-name="Card" x="10" y="20" width="50" height="40"/>
           </svg>"""
      )
    assertEquals(1, boxes.size)
    val card = boxes.single()
    assertEquals("Card", card.name)
    assertEquals(0.1f, card.left, TOLERANCE)
    assertEquals(0.1f, card.top, TOLERANCE)
    assertEquals(0.5f, card.width, TOLERANCE)
    assertEquals(0.2f, card.height, TOLERANCE)
  }

  @Test
  fun `an enclosing translate moves the boxes under it`() {
    val boxes =
      extractSvgLayoutBoxes(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
             <g transform="translate(10, 20)"><rect id="inner" width="10" height="10"/></g>
           </svg>"""
      )
    assertEquals(0.1f, boxes.single().left, TOLERANCE)
    assertEquals(0.2f, boxes.single().top, TOLERANCE)
  }

  @Test
  fun `a rotated subtree is dropped rather than approximated by its bounds`() {
    val boxes =
      extractSvgLayoutBoxes(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
             <g transform="rotate(30)"><rect width="10" height="10"/></g>
             <rect data-name="Upright" x="1" y="1" width="10" height="10"/>
           </svg>"""
      )
    assertEquals(listOf("Upright"), boxes.map { it.name })
  }

  @Test
  fun `an unparseable document yields no boxes at all`() {
    assertTrue(extractSvgLayoutBoxes("<svg><rect width='10' height='10'>").isEmpty())
    assertTrue(extractSvgLayoutBoxes("not markup").isEmpty())
  }

  @Test
  fun `hairlines and off-viewport boxes are left out`() {
    val boxes =
      extractSvgLayoutBoxes(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1000 1000">
             <rect data-name="Hairline" width="1000" height="1"/>
             <rect data-name="Offscreen" x="2000" y="0" width="100" height="100"/>
             <rect data-name="Real" x="10" y="10" width="100" height="100"/>
           </svg>"""
      )
    assertEquals(listOf("Real"), boxes.map { it.name })
  }

  @Test
  fun `an export whose policy the strict lane refuses still yields geometry`() {
    // `<image>` without the raster provenance the export gate demands: refused for publication,
    // and still perfectly readable as a layout. The two questions are answered separately on
    // purpose; see `StrictSvgParseResult.structure`.
    val boxes =
      extractSvgLayoutBoxes(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
             <image x="0" y="0" width="50" height="50" href="data:image/png;base64,AAAA"/>
             <rect data-name="Row" x="0" y="60" width="100" height="20"/>
           </svg>"""
      )
    assertEquals(listOf(null, "Row"), boxes.map { it.name })
  }
}

/** Fractions are float arithmetic over user units; a box is not wrong because it is a ulp out. */
private const val TOLERANCE = 1e-5f
