package ee.schimke.composeai.uibuilder.mcpapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class McpAppCatalogsTest {
  private val fetched = mutableListOf<String>()

  private fun catalogs(hosted: String? = null) =
    McpAppCatalogs(
      fetchAsset = { path ->
        fetched += path
        "capabilities of $path"
      },
      hostedBaseUrl = hosted,
    )

  @Test
  fun `a packaged catalog prefers the hosted definition and caches it`() {
    val catalogs = catalogs(hosted = "https://catalogs.example/")

    val first = runImmediate { catalogs.resolve("wear-m3") }
    runImmediate { catalogs.resolve("wear-m3") }

    assertEquals(McpAppCatalogs.Source.Hosted, first.source)
    assertEquals(listOf("https://catalogs.example/wear-m3-capabilities-v1.json"), fetched)
  }

  @Test
  fun `a packaged catalog remains available when the host is down`() {
    val catalogs =
      McpAppCatalogs(
        fetchAsset = { path ->
          fetched += path
          if (path.startsWith("https://")) error("host down")
          "bundled capability"
        },
        hostedBaseUrl = "https://catalogs.example/",
      )

    val resolved = runImmediate { catalogs.resolve("remote-m3") }

    assertEquals(McpAppCatalogs.Source.Bundled, resolved.source)
    assertEquals("bundled capability", resolved.capabilitiesJson)
    assertEquals(
      listOf(
        "https://catalogs.example/remote-m3-capabilities-v1.json",
        "remote-m3-capabilities-v1.json",
      ),
      fetched,
    )
  }

  @Test
  fun `an unpackaged catalog is refused by name when no hosted catalog is configured`() {
    val error =
      assertFailsWith<IllegalArgumentException> { runImmediate { catalogs().resolve("glimmer") } }

    assertTrue("'glimmer'" in error.message!!, error.message)
    assertTrue(fetched.isEmpty())
  }

  @Test
  fun `an unpackaged catalog comes from the hosted base when one is configured`() {
    val resolved = runImmediate {
      catalogs(hosted = "https://catalogs.example/").resolve("glimmer")
    }

    assertEquals(McpAppCatalogs.Source.Hosted, resolved.source)
    assertEquals(listOf("https://catalogs.example/glimmer-capabilities-v1.json"), fetched)
  }

  @Test
  fun `an unpackaged catalog does not fall back to an unrelated bundle`() {
    val catalogs =
      McpAppCatalogs(
        fetchAsset = { path ->
          fetched += path
          error("host down")
        },
        hostedBaseUrl = "https://catalogs.example/",
      )

    assertFailsWith<IllegalStateException> { runImmediate { catalogs.resolve("glimmer") } }
    assertEquals(listOf("https://catalogs.example/glimmer-capabilities-v1.json"), fetched)
  }

  @Test
  fun `a catalog id that is not a plain name is never turned into a path`() {
    assertFailsWith<IllegalArgumentException> {
      runImmediate { catalogs(hosted = "https://catalogs.example").resolve("../secrets") }
    }
    assertFailsWith<IllegalArgumentException> { runImmediate { catalogs().resolve(null) } }
    assertTrue(fetched.isEmpty())
  }
}
