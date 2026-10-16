package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class RuntimePublishedCatalogSelectionTest {
  @Test
  fun `a disabled incomplete publication cannot prevent enabled catalogs from starting`() {
    val executor =
      CurrentM3UiBuilderCatalogExecutor.Builder()
        .also {
          it.catalogSystemIds = setOf("enabled")
          it.published =
            linkedMapOf(
              "disabled" to publication("disabled", platform = null),
              "enabled" to publication("enabled", platform = "mobile"),
            )
        }
        .build()

    assertEquals(listOf("enabled"), executor.listCatalogs().map { it.benchmark.catalogSystemId })
    assertEquals(mapOf("enabled" to "published"), executor.catalogSources)
  }

  private fun publication(id: String, platform: String?): CatalogCapabilityV1 =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(id, "test", id, "test-revision", "candidate").build(),
        emptyList(),
      )
      .also {
        it.statusSemantics =
          JsonObject(platform?.let { mapOf("platform" to JsonPrimitive(it)) } ?: emptyMap())
      }
      .build()
}
