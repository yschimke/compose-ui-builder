package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.ThemeTextStyle
import ee.schimke.composeai.uibuilder.export.ThemeTypefaces
import ee.schimke.composeai.uibuilder.export.WearScreenTheme
import ee.schimke.composeai.uibuilder.protocol.CatalogBenchmarkV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ComponentCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.WasmAdapterStatusV1
import ee.schimke.composeai.uibuilder.protocol.WasmCapabilityV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The `wear-m3` authoring surface: what it admits, and what it says when asked for something else.
 */
class WearM3ScreenCatalogTest {
  private val executor =
    PublishedCatalogFixtures.executor(
      catalogSystemIds =
        setOf(
          CurrentM3UiBuilderCatalogExecutor.DEFAULT_CATALOG_SYSTEM_ID,
          CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID,
        )
    )

  private val wear =
    executor.listCatalogs().single {
      it.benchmark.catalogSystemId == CurrentM3UiBuilderCatalogExecutor.WEAR_M3_CATALOG_SYSTEM_ID
    }

  @Test
  fun `the wear catalog shelves its own components for the builder's menu`() {
    // The declaration replaces the base catalog's rather than merging (the m3 entries are keyed by
    // ids this catalog does not have).
    val menu = wear.statusSemantics["componentMenu"]?.jsonObject
    val shelved = menu?.get("components")?.jsonObject?.keys.orEmpty()

    val ids = wear.components.map { it.componentId }.toSet()
    assertTrue(shelved.isNotEmpty())
    assertTrue(shelved.all { it in ids }, "the menu names a component outside the catalog")
    assertTrue(shelved.any { it.startsWith("wear-m3/") })
    assertTrue(menu?.get("groupOrder")?.jsonArray?.isNotEmpty() == true)
    // Every variant property it names is an enum on its component, as `CatalogMenuTest` checks for
    // M3.
    val components = wear.components.associateBy { it.componentId }
    menu?.get("components")?.jsonObject?.forEach { (componentId, entry) ->
      val property = entry.jsonObject["variantProperty"]?.jsonPrimitive?.content ?: return@forEach
      val declared =
        components.getValue(componentId).properties.singleOrNull { it.name == property }
      assertNotNull(declared, "$componentId declares no property $property")
      assertTrue(
        declared.allowedValues.isNotEmpty(),
        "$componentId.$property is not an enum, so it enumerates no variants",
      )
    }
    // And it still says the other thing it says about itself; one declaration did not evict the
    // other from the map they share.
    assertTrue("previewSurfaces" in wear.statusSemantics)
  }

  @Test
  fun `the wear catalog publishes the screen scaffold and the transforming lazy column`() {
    val ids = wear.components.map { it.componentId }

    assertTrue("wear-m3/screen-scaffold" in ids, ids.toString())
    assertTrue("wear-m3/transforming-lazy-column" in ids, ids.toString())
    assertTrue(wear.benchmark.catalogRevision.startsWith("sha256:"))
  }

  /**
   * The scaffold carries the screen's furniture, not its size: the diameter is the document's
   * frame.
   */
  @Test
  fun `the screen scaffold takes no size property`() {
    val scaffold = wear.components.single { it.componentId == "wear-m3/screen-scaffold" }

    // Then its theme, one colour role per property and one typeface per group of type-scale roles
    // — still nothing that sizes it.
    assertEquals(
      listOf("timeText", "scrollIndicator", "background") +
        WearScreenTheme.ROLES.map(WearScreenTheme::property) +
        ThemeTypefaces.WEAR_GROUPS.map { it.property } +
        ThemeTextStyle.PROPERTY,
      scaffold.properties.map { it.name },
    )
    // `overlays` holds dialogs, which are written as siblings of `ScreenScaffold`, not list rows.
    assertEquals(listOf("content", "edgeButton", "overlays"), scaffold.slots.map { it.name })
    assertEquals("Scaffold", scaffold.role)
  }

  /**
   * Every component's capability note states how the canvas draws it, rather than implying parity
   * with the real AAR.
   */
  @Test
  fun `no component claims to draw the real Wear component`() {
    wear.components.forEach { component ->
      assertNotNull(component.wasm.notes, component.componentId)
      assertTrue(component.wasm.notes!!.isNotBlank(), component.componentId)
    }
  }

  /**
   * Material 3 and Wear Material 3 are not used together: only foundation (`Box`, `Column`, `Row`,
   * `Image`) is borrowed from `m3-catalog`. Asserted as a set so a new borrow is a written-down
   * decision.
   */
  @Test
  fun `the wear catalog borrows foundation and no Material component`() {
    val borrowed = wear.components.map { it.componentId }.filterNot { it.startsWith("wear-m3/") }

    assertEquals(
      // All four export; the Remote Compose seams are withdrawn because the screen generator cannot
      // write them.
      listOf("layout/box", "layout/column", "layout/row", "asset/image"),
      borrowed,
    )
    assertTrue(borrowed.none { it.startsWith("m3/") }, borrowed.toString())
  }

  /** The content components a Wear screen is built from are Wear's own, named as Wear's. */
  @Test
  fun `the wear content ids are wear ids`() {
    val wearOwn = wear.components.map { it.componentId }.filter { it.startsWith("wear-m3/") }

    assertTrue("wear-m3/text" in wearOwn, wearOwn.toString())
    assertTrue("wear-m3/card" in wearOwn, wearOwn.toString())
    assertTrue("wear-m3/button" in wearOwn, wearOwn.toString())
    // Every one of them says what it draws and what it generates, which is what keeps a stand-in
    // from being mistaken for the real component.
    wearOwn.forEach { id ->
      val notes = wear.components.single { it.componentId == id }.wasm.notes.orEmpty()
      assertTrue(notes.isNotBlank(), id)
    }
  }

  /** An id with no packaged adapter is refused by name rather than served as something else. */
  @Test
  fun `a catalog with no adapter is refused`() {
    val failure =
      assertFailsWith<IllegalArgumentException> {
        CurrentM3UiBuilderCatalogExecutor(catalogSystemIds = setOf("wear-m3-catalog"))
      }

    assertTrue("wear-m3-catalog" in failure.message.orEmpty(), failure.message.orEmpty())
    // Names both routes (published and synthesised), so an operator looks for the right fix.
    assertTrue(
      "neither published nor synthesised" in failure.message.orEmpty(),
      failure.message.orEmpty(),
    )
  }

  @Test
  fun `a published catalog is served under an id nothing here synthesises`() {
    // `test-catalog` has no synthesiser and is servable only because it was handed in: the set of
    // possible catalogs is open. Composing the published file is `:server`'s job.
    val published =
      CurrentM3UiBuilderCatalogExecutor(
          catalogSystemIds = setOf("test-catalog"),
          published = mapOf("test-catalog" to testCatalog()),
        )
        .listCatalogs()

    assertEquals(listOf("test-catalog"), published.map { it.benchmark.catalogSystemId })
  }

  @Test
  fun `a published catalog is served with the Wear foundation, and says so`() {
    val executor =
      CurrentM3UiBuilderCatalogExecutor(
        catalogSystemIds = setOf("wear-m3", "m3-catalog"),
        published = mapOf("wear-m3" to testCatalog(id = "wear-m3")),
      )

    // The source of each catalog is answerable rather than inferred from shelf contents.
    assertEquals(
      mapOf("wear-m3" to "published", "m3-catalog" to "synthesised"),
      executor.catalogSources,
    )
    val wear = executor.listCatalogs().first { it.benchmark.catalogSystemId == "wear-m3" }
    // The catalog's own component is the published one, and only that one.
    assertEquals(
      listOf("test-catalog/only"),
      wear.components.map { it.componentId }.filterNot { it in builderVocabulary },
    )
    // Plus the builder's own vocabulary, which a published shelf must not lose.
    assertEquals(
      builderVocabulary,
      wear.components.map { it.componentId }.filter { it in builderVocabulary }.toSortedSet(),
    )
  }

  /** The Wear foundation's own exported vocabulary, also pinned by the borrow test above. */
  private val builderVocabulary =
    sortedSetOf("asset/image", "layout/box", "layout/column", "layout/row")

  /** The smallest thing that is a catalog: one component and an id. */
  private fun testCatalog(id: String = "test-catalog") =
    CatalogCapabilityV1.Builder(
        "compose-catalog-capabilities/v1",
        CatalogBenchmarkV1.Builder(id, "ui-builder.json", id, "sha256:test", "candidate").build(),
        listOf(
          ComponentCapabilityV1.Builder(
              "test-catalog/only",
              "Only",
              "Leaf",
              WasmCapabilityV1.Builder(
                  platformSupported = JsonPrimitive(false),
                  adapterStatus = WasmAdapterStatusV1.UNSUPPORTED,
                )
                .build(),
            )
            .build()
        ),
      )
      .also {
        if (id == "wear-m3") {
          it.statusSemantics =
            JsonObject(
              mapOf(CurrentM3UiBuilderCatalogExecutor.PLATFORM_KEY to JsonPrimitive("wear"))
            )
        }
        it.exportCapabilities =
          ExportCapabilitiesV1.Builder()
            .also {
              it.composeCode = false
              it.svg = false
              it.png = false
            }
            .build()
      }
      .build()

  /**
   * Every Wear component the exporter writes is a component the palette offers. Literal because
   * `:ui-builder-runtime` cannot see `:ui-builder-export`; edited together with
   * `WearScreenCodeExporter.NATIVE_ONLY_COMPONENT_IDS`, and `WearComponentExportTest` checks the
   * other direction.
   */
  @Test
  fun `the palette offers every Wear component the exporter can write`() {
    val exported =
      listOf(
        "wear-m3/alert-dialog",
        "wear-m3/button-group",
        "wear-m3/checkbox-button",
        "wear-m3/confirmation-dialog",
        "wear-m3/date-picker",
        "wear-m3/edge-button",
        "wear-m3/icon",
        "wear-m3/icon-button",
        "wear-m3/list-sub-header",
        "wear-m3/open-on-phone-dialog",
        "wear-m3/progress-indicator",
        "wear-m3/radio-button",
        "wear-m3/slider",
        "wear-m3/stepper",
        "wear-m3/switch-button",
        "wear-m3/text-button",
        "wear-m3/time-picker",
      )
    assertTrue(wear.components.map { it.componentId }.toSet().containsAll(exported))
  }

  /**
   * The catalog says which renderer may claim to show a Wear design (not this browser). Read by
   * `UiBuilderPreviewSurfaces` in the editor and by `:server` to route the compile to Robolectric.
   */
  @Test
  fun `the catalog declares its canvas approximate and its native render authoritative`() {
    val surfaces = wear.statusSemantics.getValue("previewSurfaces").jsonObject

    assertEquals(
      "approximate",
      surfaces.getValue("wasm").jsonObject.getValue("fidelity").jsonPrimitive.content,
    )
    assertTrue(
      surfaces.getValue("wasm").jsonObject.getValue("reason").jsonPrimitive.content.isNotBlank()
    )
    assertEquals(
      "authoritative",
      surfaces.getValue("native").jsonObject.getValue("fidelity").jsonPrimitive.content,
    )
    // Robolectric, and not a preference: `androidx.wear.compose:compose-material3` is an Android
    // AAR, so the desktop Skiko daemon cannot compile a Wear screen at all.
    assertEquals(
      "android",
      surfaces.getValue("native").jsonObject.getValue("backend").jsonPrimitive.content,
    )
  }
}
