package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComposeSourceExportCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CatalogOwnershipTest {

  @Test
  fun `unset is none, never all`() {
    for (value in listOf(null, "", "  ", "none", "NONE")) {
      assertTrue(CatalogOwnership.parse(value).isNone, "`$value`")
      assertFalse(CatalogOwnership.parse(value).owns("wear-m3"), "`$value`")
    }
  }

  @Test
  fun `all owns every catalog, including one this build has never heard of`() {
    val all = CatalogOwnership.parse("all")
    assertTrue(all.owns("wear-m3"))
    assertTrue(all.owns("some-future-catalog"))
    assertEquals("all", all.wireValue)
  }

  @Test
  fun `a list owns exactly what it names`() {
    val some = CatalogOwnership.parse("wear-m3, remote-m3")
    assertTrue(some.owns("wear-m3"))
    assertTrue(some.owns("remote-m3"))
    assertFalse(some.owns("m3-catalog"))
    assertEquals("remote-m3,wear-m3", some.wireValue)
    assertEquals(some, CatalogOwnership.parse(some.wireValue))
  }

  @Test
  fun `a typo is refused rather than quietly owning nothing`() {
    assertFailsWith<IllegalArgumentException> { CatalogOwnership.parse("wear-m3,wear-m3") }
    assertFailsWith<IllegalArgumentException> { CatalogOwnership.parse("Wear M3") }
  }

  @Test
  fun `off the flag, routing is the built-in platform route`() {
    val wear = catalog("wear-m3", platform = "wear")
    assertEquals(
      CatalogExportRouting.Route.BuiltIn(UiBuilderCatalogPlatform.WEAR),
      CatalogExportRouting.route(wear, CatalogOwnership.NONE),
    )
  }

  @Test
  fun `an owned catalog is routed by its declaration and by nothing else`() {
    val owned = CatalogOwnership.ALL
    assertEquals(
      CatalogExportRouting.Route.NotDeclared,
      CatalogExportRouting.route(catalog("wear-m3", platform = "wear"), owned),
    )
    // A screen emitter chosen by declaration, under an id this build has never seen.
    assertEquals(
      CatalogExportRouting.Route.RecordFree(
        CatalogExportRouting.WEAR_SCREEN,
        UiBuilderCatalogPlatform.WEAR,
      ),
      CatalogExportRouting.route(
        catalog("watch-kit", platform = "wear", adapter = CatalogExportRouting.WEAR_SCREEN),
        owned,
      ),
    )
    assertIs<CatalogExportRouting.Route.ComponentRecords>(
      CatalogExportRouting.route(
        catalog("m3-catalog", adapter = CatalogComposeSourceExportAdapters.COMPOSE_MATERIAL3),
        owned,
      )
    )
    assertEquals(
      CatalogExportRouting.Route.Unsupported("wear-compose-screen", 9),
      CatalogExportRouting.route(
        catalog("wear-m3", adapter = CatalogExportRouting.WEAR_SCREEN, version = 9),
        owned,
      ),
    )
  }

  @Test
  fun `only a declared route offers export to an owned catalog`() {
    assertFalse(
      CatalogExportRouting.exportsCompose(CatalogExportRouting.Route.NotDeclared) { true }
    )
    assertTrue(
      CatalogExportRouting.exportsCompose(
        CatalogExportRouting.Route.BuiltIn(UiBuilderCatalogPlatform.WEAR)
      ) {
        true
      }
    )
  }

  private fun catalog(
    id: String,
    platform: String = "mobile",
    adapter: String? = null,
    version: Int = 1,
  ): CatalogCapabilityV1 =
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
      .also {
        it.statusSemantics = JsonObject(mapOf("platform" to JsonPrimitive(platform)))
        it.composeSourceExport = adapter?.let {
          ComposeSourceExportCapabilityV1.Builder(it, version).build()
        }
      }
      .build()
}
