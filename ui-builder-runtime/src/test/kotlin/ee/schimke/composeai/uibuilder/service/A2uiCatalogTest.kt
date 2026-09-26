package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.A2uiComposeExporter
import ee.schimke.composeai.uibuilder.export.A2uiDocumentExporter
import ee.schimke.composeai.uibuilder.export.RecordFreeExport
import ee.schimke.composeai.uibuilder.export.UiBuilderBuildFeatures
import ee.schimke.composeai.uibuilder.export.UiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.export.UiBuilderNewDesignSeed
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.CatalogCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** `a2ui-catalog`: the A2UI basic catalog, packaged, and what a design made from it can do. */
class A2uiCatalogTest {

  private fun executor(remoteJson: Boolean = false) =
    CurrentM3UiBuilderCatalogExecutor(
      catalogSystemIds = setOf(CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID),
      exportCapabilities =
        ExportCapabilitiesV1.Builder()
          .also {
            it.composeCode = true
            it.remoteJson = remoteJson
          }
          .build(),
    )

  private fun catalog(remoteJson: Boolean = false): CatalogCapabilityV1 =
    executor(remoteJson).listCatalogs().single()

  @Test
  fun `the catalog serves the eighteen basic components on the a2ui platform`() {
    val catalog = catalog()

    assertEquals("a2ui-catalog", catalog.benchmark.catalogSystemId)
    assertEquals("a2ui-basic-catalog-v0.9.1", catalog.benchmark.catalogRevision)
    assertEquals("a2ui", catalog.statusSemantics.getValue("platform").jsonPrimitive.content)
    assertEquals(
      UiBuilderCatalogPlatform.A2UI,
      UiBuilderCatalogPlatform.from(catalog.statusSemantics),
    )
    assertEquals(
      listOf(
          "Text",
          "Image",
          "Icon",
          "Video",
          "AudioPlayer",
          "Row",
          "Column",
          "List",
          "Card",
          "Tabs",
          "Modal",
          "Divider",
          "Button",
          "TextField",
          "CheckBox",
          "ChoicePicker",
          "Slider",
          "DateTimeInput",
        )
        .map { "a2ui/$it" },
      catalog.components.map { it.componentId },
    )
    // A2UI lays out through its components; nothing offers a Compose modifier.
    assertTrue(catalog.components.all { it.modifierCapabilities.isEmpty() })
    // Every shelf the published policy names, in its order, and every component on one of them.
    val menu = catalog.statusSemantics.getValue("componentMenu").jsonObject
    assertEquals(
      listOf("Layout", "Containers", "Content", "Media", "Actions", "Fields"),
      menu.getValue("groupOrder").jsonArray.map { it.jsonPrimitive.content },
    )
    assertEquals(
      catalog.components.map { it.componentId }.toSet(),
      menu.getValue("components").jsonObject.keys,
    )
    val surfaces = catalog.statusSemantics.getValue("previewSurfaces").jsonObject
    assertEquals(
      "approximate",
      surfaces.getValue("wasm").jsonObject.getValue("fidelity").jsonPrimitive.content,
    )
    assertEquals(
      "android",
      surfaces.getValue("native").jsonObject.getValue("backend").jsonPrimitive.content,
    )
  }

  @Test
  fun `the schema's shapes become properties and slots`() {
    val byId = catalog().components.associateBy { it.componentId }

    // A dynamic string binds state; an enum keeps its values; `required` is the schema's.
    val text = byId.getValue("a2ui/Text").properties.associateBy { it.name }
    assertEquals(
      listOf("string", "object"),
      text.getValue("text").jsonType.jsonArray.map { it.jsonPrimitive.content },
    )
    assertTrue(text.getValue("text").required)
    assertEquals(
      listOf("h1", "h2", "h3", "h4", "h5", "caption", "body"),
      text.getValue("variant").allowedValues.map { it.jsonPrimitive.content },
    )
    // `oneOf` takes its first typed branch: the icon name's string enum.
    val iconName = byId.getValue("a2ui/Icon").properties.single { it.name == "name" }
    assertEquals(JsonPrimitive("string"), iconName.jsonType)
    assertTrue(JsonPrimitive("favorite") in iconName.allowedValues)

    // ComponentId properties are single-child slots, ChildList ones are lists.
    val button = byId.getValue("a2ui/Button")
    assertEquals(listOf("child"), button.slots.map { it.name })
    assertEquals(1 to 1, button.slots.single().cardinality.let { it.min to it.max })
    assertTrue(button.properties.none { it.name == "child" })
    val modal = byId.getValue("a2ui/Modal")
    assertEquals(listOf("trigger", "content"), modal.slots.map { it.name })
    val column = byId.getValue("a2ui/Column").slots.single()
    assertEquals("children", column.name)
    assertEquals(0 to null, column.cardinality.let { it.min to it.max })
    // Tabs' `tabs` is a list of records, not a slot.
    val tabs = byId.getValue("a2ui/Tabs")
    assertTrue(tabs.slots.isEmpty())
    assertTrue(tabs.properties.single { it.name == "tabs" }.required)
  }

  @Test
  fun `the seed document validates and exports as A2UI JSON and as Kotlin`() {
    val executor = executor()
    val catalog = executor.listCatalogs().single()
    val fixture =
      Json.parseToJsonElement(
          File("../docs/design/fixtures/ui-builder/jetcaster-discover-operations-v1.json")
            .readText()
        )
        .jsonObject
    val seed =
      UiBuilderNewDesignSeed.document(
        designId = "a2ui-seed",
        catalogSystemId = CurrentM3UiBuilderCatalogExecutor.A2UI_CATALOG_SYSTEM_ID,
        templateId = UiBuilderNewDesignSeed.A2UI_TEMPLATE,
        catalogRevision = catalog.benchmark.catalogRevision,
        nativeRuntimeId = catalog.benchmark.nativeRuntimeId,
        fixture = fixture,
      )
    assertEquals("a2ui/Column", seed.nodes.getValue(seed.roots.single()).componentId)

    val saved = seed.toDesignDocumentV1()
    assertNull(executor.validate(saved, catalog))

    val json = assertIs<A2uiDocumentExporter.Result.Emitted>(A2uiDocumentExporter.export(seed))
    val lines = json.source.trimEnd().lines().map { Json.parseToJsonElement(it) as JsonObject }
    assertEquals(
      listOf("createSurface", "updateComponents"),
      lines.map { line -> line.keys.single { it != "version" } },
    )
    val components =
      lines.last().getValue("updateComponents").jsonObject.getValue("components").jsonArray
    assertEquals(
      "root" to "Column",
      components.first().jsonObject.let {
        it.getValue("id").jsonPrimitive.content to it.getValue("component").jsonPrimitive.content
      },
    )

    // The Compose lane the server's export already asks, for a catalog on the a2ui platform.
    assertIs<RecordFreeExport.Generated.Emitted>(
      RecordFreeExport.generate(saved, UiBuilderCatalogPlatform.A2UI, "com.example")
    )
    assertIs<A2uiComposeExporter.Result.Emitted>(A2uiComposeExporter.export(seed))
  }

  @Test
  fun `JSON export is offered for a2ui wherever the host writes JSON, without the Remote flag`() {
    assertFalse(catalog(remoteJson = false).exportCapabilities.remoteJson)
    assertFalse(catalog(remoteJson = false).supports(ExportFormatV1.JSON))

    val served = catalog(remoteJson = true)
    assertTrue(served.exportCapabilities.remoteJson)
    assertTrue(served.supports(ExportFormatV1.JSON))
    // Still not the Remote Compose binary: that is a Remote Compose document, which A2UI is not.
    assertFalse(served.supports(ExportFormatV1.RC))

    // And a mobile catalog on the same host is untouched by it.
    val m3 =
      CurrentM3UiBuilderCatalogExecutor(
          exportCapabilities =
            ExportCapabilitiesV1.Builder()
              .also {
                it.composeCode = true
                it.remoteJson = true
              }
              .build()
        )
        .listCatalogs()
        .single()
    assertFalse(m3.exportCapabilities.remoteJson && !UiBuilderBuildFeatures.remoteCompose)
  }
}
