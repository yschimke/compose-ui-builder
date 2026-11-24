package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogRecoveryRefusalTest {
  @Test
  fun `a refusal shows the service's sentence, not just its status`() {
    assertEquals(
      "catalog remote-m3 is not served by this runtime",
      catalogRecoveryRefusal(
        409,
        """{"error":"catalog remote-m3 is not served by this runtime"}""",
      ),
    )
  }

  @Test
  fun `a refusal without a readable sentence falls back to its status`() {
    assertEquals("HTTP 502", catalogRecoveryRefusal(502, "<html>Bad gateway</html>"))
    assertEquals("HTTP 409", catalogRecoveryRefusal(409, """{"error":""}"""))
  }
}
