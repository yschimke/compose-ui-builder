package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class CatalogDocumentFormatsTest {
  private val formats = BuiltInCatalogDocumentFormats.ALL

  private fun semantics(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

  private val document =
    blankUiBuilderDocument(
      "d",
      catalogPin = JsonObject(emptyMap()),
      environment = JsonObject(emptyMap()),
    )

  private val host =
    ExportCapabilitiesV1.Builder()
      .apply {
        composeCode = true
        remoteJson = true
        remoteDocument = true
      }
      .build()
      .let(formats::offerAll)

  @Test
  fun `a declaration selects the format and its policy block`() {
    val resolved =
      assertIs<CatalogDocumentFormats.Resolution.Supported>(
        formats.resolve(
          semantics(
            """{"documentFormat":{"adapter":"kobento-component-json","version":1},
               "kobento":{"clickable":["kobento/button"]}}""",
          ),
        ),
      )
    assertSame(KobentoDocumentFormat, resolved.format)
    assertEquals(semantics("""{"clickable":["kobento/button"]}"""), resolved.policy)
  }

  @Test
  fun `a catalog published before the declaration is read by its policy block`() {
    val resolved =
      assertIs<CatalogDocumentFormats.Resolution.Supported>(
        formats.resolve(semantics("""{"kobento":{}}""")),
      )
    assertSame(KobentoDocumentFormat, resolved.format)
  }

  @Test
  fun `an unknown declaration is unsupported, never guessed`() {
    val unknown =
      semantics("""{"documentFormat":{"adapter":"kobento-component-json","version":9}}""")
    assertEquals(
      CatalogDocumentFormats.Resolution.Unsupported("kobento-component-json", 9),
      formats.resolve(unknown),
    )
    assertEquals("kobento-component-json", formats.label(unknown))
    assertIs<CatalogDocumentFormat.Result.Refused>(
      formats.export(unknown, document),
    )
    // It still turns the other lanes off: the catalog said its designs are not Compose.
    val capabilities = formats.capabilities(host, unknown, host)
    assertFalse(capabilities.composeCode)
    assertFalse(capabilities.kobentoJson)
  }

  @Test
  fun `a declaring catalog exports as its format alone, where the host writes it`() {
    val kobento = semantics("""{"kobento":{}}""")
    val offered = formats.capabilities(host, kobento, host)
    assertTrue(offered.kobentoJson)
    assertFalse(offered.composeCode)
    assertFalse(offered.remoteJson)
    assertFalse(offered.remoteDocument)

    val withoutWriter = ExportCapabilitiesV1.Builder().apply { composeCode = true }.build()
    assertFalse(formats.capabilities(withoutWriter, kobento, withoutWriter).kobentoJson)
  }

  @Test
  fun `any other catalog never advertises a document format`() {
    val compose = semantics("""{"platform":"mobile"}""")
    assertEquals(CatalogDocumentFormats.Resolution.NotDeclared, formats.resolve(compose))
    assertNull(formats.label(compose))
    assertNull(formats.export(compose, document))
    // The host's flag is cleared even though the base capabilities carried it.
    val capabilities = formats.capabilities(host, compose, host)
    assertFalse(capabilities.kobentoJson)
    assertTrue(capabilities.composeCode)
  }

  @Test
  fun `a compiled document is offered only where the host compiles the format`() {
    val kobento = semantics("""{"kobento":{}}""")
    assertFalse(formats.capabilities(host, kobento, host).remoteDocument)
    assertTrue(
      formats
        .capabilities(host, kobento, host, compilers = setOf(KobentoDocumentFormat.id))
        .remoteDocument,
    )
    // A compiler for one format does not give another catalog a `.rc`.
    val compose = semantics("""{"platform":"mobile"}""")
    assertEquals(
      host.remoteDocument,
      formats
        .capabilities(host, compose, host, compilers = setOf(KobentoDocumentFormat.id))
        .remoteDocument,
    )
  }

  @Test
  fun `a declaring catalog's palette offers only what its format writes`() {
    val kobento = semantics("""{"kobento":{}}""")
    assertTrue(formats.offersOnPalette(kobento, "kobento/button"))
    assertTrue(formats.offersOnPalette(kobento, "layout/column"))
    assertFalse(formats.offersOnPalette(kobento, "draw/rect"))
    assertFalse(formats.offersOnPalette(kobento, "asset/image"))
    // A format this build does not ship hides nothing; it exports nothing either, and says so.
    val unknown =
      semantics("""{"documentFormat":{"adapter":"kobento-component-json","version":9}}""")
    assertTrue(formats.offersOnPalette(unknown, "draw/rect"))
    assertTrue(formats.offersOnPalette(semantics("""{"platform":"mobile"}"""), "draw/rect"))
  }
}
