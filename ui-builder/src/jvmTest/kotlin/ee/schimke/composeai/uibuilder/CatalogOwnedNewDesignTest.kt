package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.UiBuilderNewDesignTemplate
import ee.schimke.composeai.uibuilder.editor.catalogOwnedNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.isPrimary
import ee.schimke.composeai.uibuilder.editor.newDesignCatalogs
import ee.schimke.composeai.uibuilder.export.CatalogOwnership
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The new-design chooser under the catalog-owned flag: built from what the served catalog says,
 * ordered as the host served it, and — with the flag off — exactly the built-in chooser.
 */
class CatalogOwnedNewDesignTest {

  private fun catalog(id: String, platform: String, label: String?, templates: List<String>?) =
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
        it.statusSemantics =
          JsonObject(
            buildMap {
              put("platform", JsonPrimitive(platform))
              label?.let { put("platformLabel", JsonPrimitive(it)) }
              templates?.let { put("templates", JsonArray(it.map(::JsonPrimitive))) }
            }
          )
      }
      .build()

  private val wear =
    catalog(
      "wear-m3",
      "wear",
      "Wear OS",
      listOf("ui-builder/designs/wear-screen.json", "ui-builder/designs/wear-list.json"),
    )
  private val a2ui = catalog("a2ui-catalog", "a2ui", null, emptyList())
  private val m3 =
    catalog("m3-catalog", "mobile", "Mobile", listOf("ui-builder/designs/blank.json"))

  private fun builtIn(catalog: CatalogCapabilityV1) =
    UiBuilderNewDesignCatalog(
      systemId = catalog.benchmark.catalogSystemId,
      label = "built-in ${catalog.benchmark.catalogSystemId}",
      templates = listOf(UiBuilderNewDesignTemplate("built-in", "Built in", "")),
    )

  private val builtInOrder = listOf("m3-catalog", "wear-m3", "remote-m3", "a2ui-catalog")

  @Test
  fun `an owned card is the catalog's own templates, in its order`() {
    val card = catalogOwnedNewDesignCatalog(wear)
    assertEquals("Wear OS", card.label)
    assertEquals(UiBuilderCatalogPlatform.WEAR, card.platform)
    assertEquals(listOf("wear-screen", "wear-list"), card.templates.map { it.id })
    assertEquals(listOf("Wear Screen", "Wear List"), card.templates.map { it.label })
    assertTrue(card.catalogOwned)
    assertTrue(card.isPrimary)
  }

  @Test
  fun `a catalog that publishes no templates gets the generic blank, named after itself`() {
    val card = catalogOwnedNewDesignCatalog(a2ui)
    assertEquals("A2ui Catalog", card.label)
    assertEquals(listOf("blank"), card.templates.map { it.id })
    assertFalse(card.isPrimary)
  }

  @Test
  fun `with the flag off the chooser is the built-in one`() {
    val cards =
      newDesignCatalogs(listOf(a2ui, wear, m3), CatalogOwnership.NONE, builtInOrder, ::builtIn)
    assertEquals(listOf("m3-catalog", "wear-m3", "a2ui-catalog"), cards.map { it.systemId })
    assertTrue(cards.all { !it.catalogOwned && it.templates.single().id == "built-in" })
  }

  @Test
  fun `with the flag on, cards come from the catalogs in the order they were served`() {
    val cards =
      newDesignCatalogs(listOf(a2ui, wear, m3), CatalogOwnership.ALL, builtInOrder, ::builtIn)
    assertEquals(listOf("a2ui-catalog", "wear-m3", "m3-catalog"), cards.map { it.systemId })
    assertTrue(cards.all { it.catalogOwned })
  }

  @Test
  fun `the flag can move one catalog at a time`() {
    val cards =
      newDesignCatalogs(
        listOf(wear, m3),
        CatalogOwnership.of(setOf("wear-m3")),
        builtInOrder,
        ::builtIn,
      )
    assertEquals(listOf(false, true), cards.map { it.catalogOwned })
  }
}
