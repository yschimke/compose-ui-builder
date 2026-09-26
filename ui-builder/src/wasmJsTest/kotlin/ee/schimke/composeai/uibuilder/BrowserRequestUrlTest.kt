@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half of the editor's URL building that is a pure function, and the half that was wrong.
 *
 * [catalogAssetPath] shipped for months emitting `${'$'}{…}` — a literal `$` in an ordinary Kotlin
 * string, not an interpolation — so the browser requested the path
 * `/$%7BencodeUriComponent(catalogSystemId)%7D$path$query` and took a 404. Nothing caught it
 * because its only caller loads the Remote Compose palette and treats any failure as "this box
 * serves no such catalog", so the symptom was an always-empty panel rather than an error.
 *
 * These run on wasmJs rather than in `commonTest` because the function encodes through the
 * browser's own `encodeURIComponent`; a JVM stand-in would be testing a different implementation
 * than the one that ships. The performance harness asserts [sameOriginRequestUrl]'s effect end to
 * end, by failing on any 401 the editor provokes against its own server; the tests here that read
 * `window.location` only pin down that the page's `?token=` is never copied onto a request.
 */
class BrowserRequestUrlTest {

  @Test
  fun `a catalog asset path interpolates rather than quoting its own source`() {
    assertEquals("/m3-catalog/api/previews", catalogAssetPath("m3-catalog", "/api/previews"))
    assertEquals("/remote-m3/render/hero.rc", catalogAssetPath("remote-m3", "/render/hero.rc"))
  }

  @Test
  fun `no catalog asset path contains an un-interpolated template`() {
    val path = catalogAssetPath("m3-catalog", "/render/hero.rc")

    // The specific failure, named: a `$` reaching the wire from this function means a template was
    // escaped into a literal again. No legal catalog id or route path produces one.
    assertFalse(path.contains('$'), path)
    assertFalse(path.contains("encodeUriComponent"), path)
  }

  @Test
  fun `a catalog id is encoded, so it cannot open a path of its own`() {
    // `UI_BUILDER_CATALOG_ID` keeps these out on the server side, but this function is also the
    // one that would carry such an id into a request if the rule ever loosened.
    assertEquals("/a%2Fb/api/previews", catalogAssetPath("a/b", "/api/previews"))
    assertEquals("/a%3Fb/api/previews", catalogAssetPath("a?b", "/api/previews"))
  }

  @Test
  fun `OpenCode prompt hands an agent a credential-free MCP design handoff`() {
    val prompt =
      openCodeUiBuilderPrompt(
        mcpEndpoint = "https://preview.example/mcp",
        designUrl = "https://preview.example/ui-builder/settings",
        designId = "settings",
      )

    assertTrue(
      prompt.contains("https://github.com/yschimke/skills/tree/main/skills/compose-ui-builder")
    )
    assertTrue(prompt.contains("https://preview.example/mcp"))
    assertTrue(prompt.contains("https://preview.example/ui-builder/settings"))
    assertTrue(prompt.contains("ui-builder-read`"))
    assertTrue(prompt.contains("ui-builder-write`"))
    assertTrue(prompt.contains("ui-builder-export`"))
    assertFalse(prompt.contains("token="))
  }

  @Test
  fun `request URLs never copy the page token`() {
    withPageQuery("?token=operator-secret&actor=github%3Aa") {
      val request = sameOriginRequestUrl("/api/ui-builder/v1/identity")
      assertFalse(request.contains("token="), request)
      assertTrue(request.endsWith("/api/ui-builder/v1/identity"), request)
      // A caller that built its own query keeps it.
      assertTrue(sameOriginRequestUrl("/x?token=explicit").endsWith("/x?token=explicit"))

      val socket = nativeStreamUrl("session", "preview")
      assertFalse(socket.contains("token="), socket)
      assertTrue(socket.contains("/session/ws/preview?codec=webp"), socket)
    }
  }

  @Test
  fun `the page token is taken out of the address bar and nothing else is`() {
    withPageQuery("?token=operator-secret&node=hero") {
      stripPageToken()
      val location = currentLocation()
      assertFalse(location.contains("token"), location)
      assertTrue(location.contains("node=hero"), location)
    }
  }

  @Test
  fun `the page token is not an identity key carried across navigations`() {
    assertFalse("token" in DESIGN_URL_IDENTITY_KEYS)
  }

  private fun withPageQuery(query: String, block: () -> Unit) {
    val original = currentLocation()
    try {
      replaceLocation(query)
      block()
    } finally {
      replaceLocation(original)
    }
  }
}

@JsFun("() => window.location.href") private external fun currentLocation(): String

@JsFun("(value) => window.history.replaceState(null, '', value)")
private external fun replaceLocation(value: String)
