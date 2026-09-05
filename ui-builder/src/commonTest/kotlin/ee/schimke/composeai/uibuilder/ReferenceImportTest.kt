package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReferenceImportTest {
  @Test
  fun `raster types under the limit are accepted`() {
    assertNull(referenceImportRefusal("image/png", 1024, null))
    assertNull(referenceImportRefusal("image/jpeg", 1024, null))
  }

  @Test
  fun `anything that is not a picture is refused by type`() {
    assertNotNull(referenceImportRefusal("application/pdf", 1024, null))
    assertNotNull(referenceImportRefusal("text/html", 1024, null))
  }

  @Test
  fun `an oversized import is refused before it is decoded`() {
    assertNotNull(referenceImportRefusal("image/png", MAX_REFERENCE_BYTES + 1, null))
  }

  @Test
  fun `a plain SVG is accepted`() {
    assertNull(
      referenceSvgRefusal(
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect width="10" height="10"/></svg>"""
      )
    )
  }

  @Test
  fun `active content and external references are refused`() {
    val script = referenceSvgRefusal("""<svg viewBox="0 0 1 1"><script>alert(1)</script></svg>""")
    assertNotNull(script)
    val handler =
      referenceSvgRefusal(
        """<svg viewBox="0 0 1 1"><rect onclick="x()" width="1" height="1"/></svg>"""
      )
    assertNotNull(handler)
    val external =
      referenceSvgRefusal(
        """<svg viewBox="0 0 1 1"><image href="https://example.test/a.png" width="1" height="1"/></svg>"""
      )
    assertNotNull(external)
  }

  @Test
  fun `a fragment reference and an embedded picture stay inside the file`() {
    assertNull(
      referenceSvgRefusal(
        """<svg viewBox="0 0 1 1"><use href="#a"/><image href="data:image/png;base64,AA" width="1" height="1"/></svg>"""
      )
    )
  }

  @Test
  fun `an SVG that will not parse is refused rather than half-read`() {
    assertEquals(
      "The SVG could not be parsed as well-formed markup.",
      referenceSvgRefusal("<svg><rect>"),
    )
  }
}
