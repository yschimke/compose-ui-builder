package ee.schimke.composeai.uibuilder.local

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The one thing an offline session cannot derive.
 *
 * A design is component ids and property values; the catalog is what they mean. Without a
 * remembered copy, a reload with the server unreachable has a document it cannot draw, so this is
 * the piece that decides whether "mostly offline" is true at all.
 */
class CachingLocalCatalogSourceTest {
  private val storage = InMemoryLocalDesignStorage()
  private var online = true
  private val source =
    CachingLocalCatalogSource(
      storage,
      { if (online) listOf(catalog) else throw IllegalStateException("no network") },
    )

  @Test
  fun `the network answer is preferred and remembered, and serves the next offline open`() =
    runBlocking {
      assertEquals(listOf(catalog), source.catalogs())
      assertFalse(source.servedFromStorage)

      online = false

      assertEquals(listOf(catalog), source.catalogs())
      assertTrue(source.servedFromStorage)
    }

  @Test
  fun `a browser that never saw a catalog says so rather than pretending`() = runBlocking {
    online = false

    val failure = assertFailsWith<LocalDesignStorageException> { source.catalogs() }

    assertTrue(failure.message!!.contains("no stored catalog"))
  }

  private companion object {
    val catalog =
      CatalogCapabilityV1(
        schema = "compose-ui-builder-catalog/v1",
        benchmark =
          CatalogBenchmarkV1(
            id = "test",
            sourceRevision = "test",
            catalogSystemId = "m3-catalog",
            catalogRevision = "test",
            nativeRuntimeId = "test",
          ),
        components = emptyList(),
      )
  }
}
