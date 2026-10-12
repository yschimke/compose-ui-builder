package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CatalogListingTest {

  private fun catalog(id: String, semantics: Map<String, JsonPrimitive> = emptyMap()) =
    CatalogCapabilityV1.Builder(
        schema = "compose-ui-builder-capability/v1",
        benchmark =
          CatalogBenchmarkV1.Builder(
              id = id,
              sourceRevision = "source",
              catalogSystemId = id,
              catalogRevision = "revision",
              nativeRuntimeId = "runtime",
            )
            .build(),
        components = emptyList(),
      )
      .also { it.statusSemantics = JsonObject(semantics) }
      .build()

  @Test
  fun `an unmarked catalog is listed`() {
    assertFalse(CatalogListing.isUnlisted(catalog("wear-m3")))
    assertFalse(
      CatalogListing.isUnlisted(
        catalog("wear-m3", mapOf(CatalogListing.UNLISTED_KEY to JsonPrimitive("true")))
      ),
      "only a JSON true unlists",
    )
  }

  @Test
  fun `marking unlists and keeps the rest of the catalog`() {
    val plain = catalog("wear-m3-typed-pilot", mapOf("platform" to JsonPrimitive("wear")))
    val marked = CatalogListing.markUnlisted(plain)
    assertTrue(CatalogListing.isUnlisted(marked))
    assertEquals(JsonPrimitive("wear"), marked.statusSemantics["platform"])
    assertSame(marked, CatalogListing.markUnlisted(marked))
  }

  @Test
  fun `listed drops only the unlisted, in order`() {
    val a = catalog("a")
    val b = CatalogListing.markUnlisted(catalog("b"))
    val c = catalog("c")
    assertEquals(listOf(a, c), CatalogListing.listed(listOf(a, b, c)))
  }

  @Test
  fun `parse reads a comma-separated flag, unset meaning none`() {
    assertEquals(emptySet(), CatalogListing.parse(null))
    assertEquals(emptySet(), CatalogListing.parse("  "))
    assertEquals(
      setOf("wear-m3-typed-pilot", "remote-m3-typed-pilot"),
      CatalogListing.parse(" wear-m3-typed-pilot, remote-m3-typed-pilot ,"),
    )
    assertFailsWith<IllegalArgumentException> { CatalogListing.parse("Wear M3") }
  }
}
