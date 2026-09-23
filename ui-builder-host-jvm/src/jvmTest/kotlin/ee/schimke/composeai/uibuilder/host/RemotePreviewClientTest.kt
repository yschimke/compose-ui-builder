package ee.schimke.composeai.uibuilder.host

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemotePreviewClientTest {
  @Test
  fun `server configuration is one secure origin`() {
    assertEquals(URI("https://preview.example"), validatedServerOrigin("https://preview.example/"))
    assertEquals(URI("http://localhost:8080"), validatedServerOrigin("http://localhost:8080"))
    assertEquals(URI("http://[::1]:8080"), validatedServerOrigin("http://[::1]:8080"))

    listOf(
        "http://preview.example",
        "https://preview.example/path",
        "https://user@preview.example",
        "https://preview.example?token=secret",
        "https:preview.example",
      )
      .forEach { value ->
        assertFailsWith<IllegalArgumentException>(value) { validatedServerOrigin(value) }
      }
  }

  @Test
  fun `device grant polling cannot leave the configured origin`() {
    val origin = URI("https://preview.example")
    assertEquals(
      URI("https://preview.example/agent-access/poll?id=7"),
      sameOriginTarget(origin, URI("/agent-access/poll?id=7")),
    )
    assertEquals(
      URI("https://preview.example:443/agent-access/poll"),
      sameOriginTarget(origin, URI("https://preview.example:443/agent-access/poll")),
    )

    listOf(
        "https://attacker.invalid/poll",
        "//attacker.invalid/poll",
        "http://preview.example/poll",
        "https://preview.example:444/poll",
        "https://user@preview.example/poll",
      )
      .forEach { value ->
        assertFailsWith<IllegalArgumentException>(value) { sameOriginTarget(origin, URI(value)) }
      }
  }
}
