package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.catalogOwnedNewDesignCatalog
import ee.schimke.composeai.uibuilder.editor.isPrimary
import ee.schimke.composeai.uibuilder.editor.newDesignCatalogs
import ee.schimke.composeai.uibuilder.editor.revealedNewDesignCatalogs
import ee.schimke.composeai.uibuilder.export.CatalogListing
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

/** The chooser uses only published catalog metadata, including order and visibility. */
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

  @Test
  fun `numeric template ids keep their published list order`() {
    val card =
      catalogOwnedNewDesignCatalog(
        catalog("numbered", "mobile", null, listOf("10.json", "2.json", "1.json"))
      )
    assertEquals(listOf("10", "2", "1"), card.templates.map { it.id })
  }

  @Test
  fun `the current chooser needs no ownership flag or built-in catalog table`() {
    val cards = newDesignCatalogs(listOf(a2ui, wear, m3))
    assertEquals(listOf("a2ui-catalog", "wear-m3", "m3-catalog"), cards.map { it.systemId })
    assertEquals(listOf("wear-screen", "wear-list"), cards[1].templates.map { it.id })
  }

  @Test
  fun `an owned card is the catalog's own templates, in its order`() {
    val card = catalogOwnedNewDesignCatalog(wear)
    assertEquals("Wear OS", card.label)
    assertEquals(UiBuilderCatalogPlatform.WEAR, card.platform)
    assertEquals(listOf("wear-screen", "wear-list"), card.templates.map { it.id })
    assertEquals(listOf("Wear Screen", "Wear List"), card.templates.map { it.label })
    assertFalse(card.isPrimary)
  }

  @Test
  fun `a catalog that publishes no templates gets the generic blank, named after itself`() {
    val card = catalogOwnedNewDesignCatalog(a2ui)
    assertEquals("A2ui Catalog", card.label)
    assertEquals(listOf("blank"), card.templates.map { it.id })
    assertFalse(card.isPrimary)
  }

  private val wearPilot =
    CatalogListing.markUnlisted(catalog("wear-m3-typed-pilot", "wear", "Wear OS", null))

  @Test
  fun `an unlisted catalog gets no card`() {
    val cards = newDesignCatalogs(listOf(wear, wearPilot, m3))
    assertEquals(setOf("m3-catalog", "wear-m3"), cards.map { it.systemId }.toSet())
  }

  @Test
  fun `an unlisted catalog is offered on its own page`() {
    val cards =
      newDesignCatalogs(
        listOf(wear, wearPilot, m3),
        revealed = setOf("wear-m3-typed-pilot"),
      )
    assertTrue("wear-m3-typed-pilot" in cards.map { it.systemId })
  }

  @Test
  fun `only a catalog the URL named is revealed, never the default`() {
    assertEquals(emptySet(), revealedNewDesignCatalogs(null))
    assertEquals(emptySet(), revealedNewDesignCatalogs(""))
    val unlistedM3 = CatalogListing.markUnlisted(m3)
    val plain =
      newDesignCatalogs(
        listOf(wear, unlistedM3),
        revealed = revealedNewDesignCatalogs(null),
      )
    assertEquals(listOf("wear-m3"), plain.map { it.systemId })
    val named =
      newDesignCatalogs(
        listOf(wear, unlistedM3),
        revealed = revealedNewDesignCatalogs("m3-catalog"),
      )
    assertEquals(listOf("wear-m3", "m3-catalog"), named.map { it.systemId })
  }

  @Test
  fun `by default every served catalog is listed`() {
    val cards =
      newDesignCatalogs(listOf(wear, catalog("wear-m3-typed-pilot", "wear", "Wear OS", null), m3))
    assertEquals(3, cards.size)
  }

  private fun withNewDesign(catalog: CatalogCapabilityV1, newDesign: String) =
    catalog
      .newBuilder()
      .also {
        it.statusSemantics =
          JsonObject(
            catalog.statusSemantics +
              ("newDesign" to kotlinx.serialization.json.Json.parseToJsonElement(newDesign))
          )
      }
      .build()

  @Test
  fun `a catalog that publishes its chooser copy is carded by it, in the order it gives`() {
    val described =
      withNewDesign(
        wear,
        """
        {"label": "Wear app", "order": 1, "templates": [
          {"id": "wear-screen", "path": "ui-builder/designs/wear-screen.json"},
          {"id": "wear-list", "path": "ui-builder/designs/wear-list.json", "label": "Activity list",
           "supportingText": "Six title cards under a list header.", "group": "Lists", "order": 1}
        ]}
        """,
      )
    val card = catalogOwnedNewDesignCatalog(described)
    assertEquals("Wear app", card.label)
    // `wear-list` gives an order and `wear-screen` none, so the ordered one comes first.
    assertEquals(listOf("wear-list", "wear-screen"), card.templates.map { it.id })
    assertEquals("Activity list", card.templates[0].label)
    assertEquals("Six title cards under a list header.", card.templates[0].supportingText)
    assertEquals("Lists", card.templates[0].group)
    // An entry that says nothing keeps the file-name card.
    assertEquals("Wear Screen", card.templates[1].label)
  }

  @Test
  fun `with every catalog owned, the catalogs' own order decides before the served order`() {
    val cards =
      newDesignCatalogs(
        listOf(
          withNewDesign(wear, """{"order": 2}"""),
          withNewDesign(m3, """{"order": 1}"""),
          a2ui,
        )
      )
    assertEquals(listOf("m3-catalog", "wear-m3", "a2ui-catalog"), cards.map { it.systemId })
  }

  @Test
  fun `an ordered entry precedes an unordered one whatever its order value`() {
    val described =
      withNewDesign(
        wear,
        """{"templates": [{"id": "wear-list", "path": "p", "order": ${Int.MAX_VALUE - 1}}]}""",
      )
    // `wear-screen` is listed first and gives no order; `wear-list` gives a very large one.
    assertEquals(
      listOf("wear-list", "wear-screen"),
      catalogOwnedNewDesignCatalog(described).templates.map { it.id },
    )
    val cards =
      newDesignCatalogs(listOf(a2ui, withNewDesign(m3, """{"order": ${Int.MAX_VALUE}}""")))
    assertEquals(listOf("m3-catalog", "a2ui-catalog"), cards.map { it.systemId })
  }

  @Test
  fun `a catalog can describe its type card and promote itself into the row`() {
    val ha =
      withNewDesign(
        catalog("ha-cards", "mobile", null, listOf("ui-builder/designs/blank.json")),
        """{"label": "Home Assistant", "description": "Cards, widgets and tiles", "primary": true}""",
      )
    val card = catalogOwnedNewDesignCatalog(ha)
    assertEquals("Home Assistant", card.label)
    assertEquals("Cards, widgets and tiles", card.description)
    assertEquals(true, card.primary)
    assertTrue(card.isPrimary)
    // Saying nothing leaves both to the defaults: no second line of its own, not in the row.
    val quiet = catalogOwnedNewDesignCatalog(catalog("ha-cards", "mobile", null, null))
    assertEquals(null, quiet.description)
    assertEquals(null, quiet.primary)
    assertFalse(quiet.isPrimary)
    // A built-in primary catalog can step out of the row.
    assertFalse(
      catalogOwnedNewDesignCatalog(withNewDesign(wear, """{"primary": false}""")).isPrimary
    )
  }
}
