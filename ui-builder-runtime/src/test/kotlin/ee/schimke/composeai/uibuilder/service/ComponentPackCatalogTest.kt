package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.DesignNodeV1
import ee.schimke.composeai.uibuilder.protocol.PropertyCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.SlotCardinalityV1
import ee.schimke.composeai.uibuilder.protocol.StringValueV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Component packs: another catalog's components merged into the authoring catalogs of the same
 * platform, and nowhere else.
 */
class ComponentPackCatalogTest {
  private val all =
    setOf(
      CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.REMOTE_M3_CATALOG_SYSTEM_ID,
      CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
    )

  @Test
  fun `every packaged catalog declares its platform`() {
    val catalogs = CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = all).listCatalogs()
    assertEquals(
      mapOf("m3-catalog" to "mobile", "remote-m3" to "remote-compose", "wear-m3" to "wear"),
      catalogs.associate { it.benchmark.catalogSystemId to it.platform },
    )
  }

  @Test
  fun `a mobile pack lands in the mobile catalog only, shelved under its own name`() {
    val executor =
      CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = all, packs = listOf(confetti()))
    val byId = executor.listCatalogs().associateBy { it.benchmark.catalogSystemId }

    val m3 = byId.getValue("m3-catalog")
    assertTrue(m3.components.any { it.componentId == "confetti-mobile/session-card" })
    assertTrue(m3.components.any { it.componentId == "confetti-mobile/speaker-row" })
    val menu = m3.statusSemantics.getValue("componentMenu").jsonObject
    assertEquals(
      "Confetti Mobile",
      menu.getValue("groupOrder").jsonArray.last().jsonPrimitive.content,
    )
    assertEquals(
      "Confetti Mobile",
      menu
        .getValue("components")
        .jsonObject
        .getValue("confetti-mobile/session-card")
        .jsonObject
        .getValue("group")
        .jsonPrimitive
        .content,
    )
    val declared = m3.statusSemantics.getValue("componentPacks").jsonArray.single().jsonObject
    assertEquals("confetti-mobile", declared.getValue("id").jsonPrimitive.content)
    assertEquals("confetti-mobile", declared.getValue("nativeCatalog").jsonPrimitive.content)
    assertEquals(
      listOf("confetti-mobile/session-card", "confetti-mobile/speaker-row"),
      declared.getValue("components").jsonArray.map { it.jsonPrimitive.content },
    )
    // The m3 shelves the catalog declared itself are all still there, ahead of the pack's.
    assertEquals(
      "Scaffolds",
      menu.getValue("groupOrder").jsonArray.first().jsonPrimitive.content,
    )

    // Neither the Wear screen nor the Wear widget can call a phone application's composables.
    listOf("remote-m3", "wear-m3").forEach { other ->
      val catalog = byId.getValue(other)
      assertTrue(catalog.components.none { it.componentId.startsWith("confetti-mobile/") }, other)
      assertNull(catalog.statusSemantics["componentPacks"], other)
    }
  }

  @Test
  fun `a design holding a pack component validates against its pinned catalog`() {
    val executor =
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("m3-catalog"),
        packs = listOf(confetti()),
      )
    val catalog = executor.listCatalogs().single()
    val base = ProductionUiBuilderRuntimeTest.document()
    val withPack =
      base.copy(
        nodes =
          base.nodes +
            ("text" to
              DesignNodeV1(
                id = "text",
                componentId = "confetti-mobile/session-card",
                properties = mapOf("title" to StringValueV1("Compose everywhere")),
              ))
      )

    assertEquals(catalog, executor.resolve(withPack.catalogPin))
    assertNull(executor.validate(withPack, catalog))
    assertEquals(
      "MISSING_REQUIRED_PROPERTY",
      executor
        .validate(
          withPack.copy(
            nodes =
              withPack.nodes +
                ("text" to withPack.nodes.getValue("text").copy(properties = emptyMap()))
          ),
          catalog,
        )
        ?.code,
    )
  }

  @Test
  fun `a pack is refused when it collides with a catalog, an enabled catalog or itself`() {
    assertFailsWith<IllegalArgumentException> {
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("m3-catalog"),
        packs = listOf(confetti().copy(id = "m3-catalog", components = emptyList())),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("m3-catalog"),
        packs = listOf(confetti(), confetti()),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      UiBuilderComponentPackSource(
        id = "confetti-mobile",
        label = "Confetti Mobile",
        platform = "mobile",
        nativeCatalog = null,
        components = listOf(component("m3/text")),
      )
    }
    assertFailsWith<IllegalArgumentException> {
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("m3-catalog"),
        packs = listOf(confetti().copy(id = "bad/id", components = emptyList())),
      )
    }
  }

  @Test
  fun `a pack for a platform no enabled catalog has is simply not offered`() {
    val executor =
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("m3-catalog"),
        packs =
          listOf(
            confetti().copy(id = "confetti-wear", platform = "wear", components = emptyList())
          ),
      )
    val catalog = executor.listCatalogs().single()
    assertNull(catalog.statusSemantics["componentPacks"])
    assertEquals(
      CurrentM3UiBuilderCatalogExecutor().listCatalogs().single().components,
      catalog.components,
    )
  }

  private fun confetti(): UiBuilderComponentPackSource =
    UiBuilderComponentPackSource(
      id = "confetti-mobile",
      label = "Confetti Mobile",
      platform = "mobile",
      nativeCatalog = "confetti-mobile",
      components =
        listOf(
          component(
            "confetti-mobile/session-card",
            properties =
              listOf(
                PropertyCapabilityV1(
                  name = "title",
                  jsonType = JsonPrimitive("string"),
                  required = true,
                )
              ),
          ),
          component(
            "confetti-mobile/speaker-row",
            slots =
              listOf(
                SlotCapabilityV1(
                  name = "content",
                  cardinality = SlotCardinalityV1(),
                  ordered = true,
                )
              ),
          ),
        ),
      notes = "Derived from the served catalog's component record.",
    )

  private fun component(
    id: String,
    properties: List<PropertyCapabilityV1> = emptyList(),
    slots: List<SlotCapabilityV1> = emptyList(),
  ): ComponentCapabilityV1 =
    ComponentCapabilityV1(
      componentId = id,
      displayName = id.substringAfter('/'),
      role = if (slots.isEmpty()) "Leaf" else "Container",
      slots = slots,
      properties = properties,
      modifierCapabilities = listOf("padding", "fillMaxWidth"),
      wasm =
        WasmCapabilityV1(
          platformSupported = JsonPrimitive(false),
          adapterStatus = WasmAdapterStatusV1.UNSUPPORTED,
        ),
    )

  private val CatalogCapabilityV1.platform: String
    get() = statusSemantics.getValue("platform").jsonPrimitive.content
}
