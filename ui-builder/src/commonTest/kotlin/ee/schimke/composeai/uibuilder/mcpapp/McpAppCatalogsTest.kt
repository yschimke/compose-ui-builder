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
  fun `a packaged catalog is read from the editor's own assets, once`() {
    val catalogs = catalogs(hosted = "https://catalogs.example/")

    val first = runImmediate { catalogs.resolve("wear-m3") }
    runImmediate { catalogs.resolve("wear-m3") }

    assertEquals(McpAppCatalogs.Source.Bundled, first.source)
    assertEquals(listOf("wear-m3-capabilities-v1.json"), fetched)
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
  fun `a catalog id that is not a plain name is never turned into a path`() {
    assertFailsWith<IllegalArgumentException> {
      runImmediate { catalogs(hosted = "https://catalogs.example").resolve("../secrets") }
    }
    assertFailsWith<IllegalArgumentException> { runImmediate { catalogs().resolve(null) } }
    assertTrue(fetched.isEmpty())
  }
}
