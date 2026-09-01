@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

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

  @Test
  fun `page token is encoded on the same-origin WebSocket without entering diagnostics`() {
    val original = browserLocation()
    try {
      replaceBrowserLocation("?token=operator%20token%26scope%3Dwrite")
      val url =
        browserUiBuilderWebSocketUrl(
          endpoint = "/api/ui-builder/v1/designs/{designId}/updates",
          designId = "design",
          afterSequence = "7",
          hasAfterSequence = true,
        )

      assertTrue(url.contains("token=operator+token%26scope%3Dwrite"))
      assertFalse(url.contains("operator token"))
      assertFalse(browserUiBuilderTransportFailureMessage().contains("operator"))
      assertFalse(browserUiBuilderTransportFailureMessage().contains("scope"))
    } finally {
      replaceBrowserLocation(original)
    }
  }

  @Test
  fun `credentialed transport rejects cross-origin WebSocket endpoints`() {
    assertFailsWith<Throwable> {
      browserUiBuilderWebSocketUrl(
        endpoint = "https://attacker.invalid/api/ui-builder/v1/designs/{designId}/updates",
        designId = "design",
        afterSequence = "",
        hasAfterSequence = false,
      )
    }
  }
}

@JsFun("() => window.location.href") private external fun browserLocation(): String

@JsFun("(value) => window.history.replaceState(null, '', value)")
private external fun replaceBrowserLocation(value: String)
