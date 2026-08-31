package ee.schimke.composeai.uibuilder.client

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserUiBuilderTransportTest {
  @Test
  fun `WebSocket URL keeps an exact exclusive cursor and encodes the design id`() {
    val url =
      browserUiBuilderWebSocketUrl(
        endpoint = "/api/ui-builder/v1/designs/{designId}/updates?existing=value",
        designId = "design / one",
        afterSequence = "9007199254740993",
        hasAfterSequence = true,
      )

    assertTrue(url.startsWith("ws://") || url.startsWith("wss://"))
    assertTrue(url.contains("existing=value"))
    assertTrue(url.contains("/designs/design%20%2F%20one/updates"))
    assertTrue(url.contains("afterSequence=9007199254740993"))
  }

  @Test
  fun `WebSocket URL omits the cursor when a current snapshot is requested`() {
    val url =
      browserUiBuilderWebSocketUrl(
        endpoint = "/api/ui-builder/v1/designs/{designId}/updates?afterSequence=stale",
        designId = "design",
        afterSequence = "",
        hasAfterSequence = false,
      )

    assertFalse(url.contains("afterSequence="))
    assertTrue(url.contains("/designs/design/updates"))
  }

  @Test
  fun `WebSocket URL requires the server design path template`() {
    assertFailsWith<Throwable> {
      browserUiBuilderWebSocketUrl(
        endpoint = "/api/ui-builder/v1/updates",
        designId = "design",
        afterSequence = "",
        hasAfterSequence = false,
      )
    }
  }
}
