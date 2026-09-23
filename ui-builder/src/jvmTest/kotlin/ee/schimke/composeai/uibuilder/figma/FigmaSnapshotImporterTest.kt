package ee.schimke.composeai.uibuilder.figma

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.capability.CapabilityValidator
import ee.schimke.composeai.uibuilder.codegen.CapabilityComposeCodeExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.optionalString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Figma frame imports as an operation log that replays, validates against the catalog it pins,
 * and exports as Compose — and says exactly what it could not carry across.
 */
class FigmaSnapshotImporterTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val map = FigmaComponentMap.parse(resource("/m3-catalog-figma-map-v1.json"))
  private val snapshot = FigmaSnapshot.parse(resource("/figma/checkout-snapshot-v1.json"))
  private val result = FigmaSnapshotImporter(catalog, map).import(snapshot, designId = "checkout")
  private val document: UiBuilderDocument = UiBuilderReducer.replay(result.operations).document

  @Test
  fun `the import replays and validates against the pinned catalog`() {
    assertEquals("checkout", document.id)
    assertEquals("Checkout", document.title)
    assertEquals(listOf("figma-10-1"), document.roots)
    assertEquals(emptyList(), CapabilityValidator(catalog).validate(document).issues)
  }

  @Test
  fun `and exports as Compose`() {
    val source = CapabilityComposeCodeExporter.export(document, catalog).requireSource()
    assertTrue("@Composable" in source)
    assertTrue("Button(" in source, source)
    assertTrue("Checkbox(" in source, source)
  }

  @Test
  fun `auto layout becomes Row and Column, keeping spacing, padding and alignment`() {
    val root = node("figma-10-1")
    assertEquals("layout/column", root.componentId)
    assertEquals("16.0", root.properties.value("verticalSpacingDp"))
    assertEquals("start", root.properties.value("horizontalAlignment"))
    assertEquals(
      listOf("fillMaxSize", "background", "padding"),
      root.modifiers.map { it.jsonObject.optionalString("type") },
    )
    assertEquals(
      "surface",
      root.modifiers[1].jsonObject["color"]!!.jsonObject.optionalString("value"),
    )

    val line = node("figma-10-3")
    assertEquals("layout/row", line.componentId)
    assertEquals("spaceBetween", line.properties.value("horizontalArrangement"))
    assertEquals("center", line.properties.value("verticalAlignment"))
    assertEquals(
      listOf("fillMaxWidth"),
      line.modifiers.map { it.jsonObject.optionalString("type") },
    )
  }

  @Test
  fun `children keep their Figma order`() {
    assertEquals(
      listOf(
        "figma-10-2",
        "figma-10-3",
        "figma-10-6",
        "figma-10-7",
        "figma-10-10",
        "figma-10-11",
        "figma-10-12",
        "figma-10-13",
      ),
      node("figma-10-1").slots["children"],
    )
  }

  @Test
  fun `a mapped instance becomes its catalog component with its label`() {
    val pay = node("figma-10-13")
    assertEquals("m3/button", pay.componentId)
    assertEquals("filled", pay.properties.value("style"))
    assertEquals("true", pay.properties.value("enabled"))
    // A fixed-size kit instance keeps its intrinsic size; filling the row is the design's intent.
    assertEquals(listOf("fillMaxWidth"), pay.modifiers.map { it.jsonObject.optionalString("type") })
    val label = node(pay.slots.getValue("content").single())
    assertEquals("m3/text", label.componentId)
    assertEquals("Pay $42.00", label.properties.value("text"))

    val checkbox = node("figma-10-8")
    assertEquals("m3/checkbox", checkbox.componentId)
    assertEquals("true", checkbox.properties.value("checked"))
  }

  @Test
  fun `text takes its style from the map, and its size and weight when unmapped`() {
    val title = node("figma-10-2")
    assertEquals("headlineMedium", title.properties.value("style"))
    assertNull(title.properties["fontSizeSp"])
    assertEquals("onSurface", title.properties.value("color"))

    val amount = node("figma-10-5")
    assertEquals("16.0", amount.properties.value("fontSizeSp"))
    assertEquals("semiBold", amount.properties.value("fontWeight"))
    assertEquals("end", amount.properties.value("textAlign"))
  }

  @Test
  fun `a shape becomes a painted box, and an unmapped layer a sized hole with a diagnostic`() {
    val promo = node("figma-10-10")
    assertEquals("layout/box", promo.componentId)
    val background = promo.modifiers.single { it.jsonObject.optionalString("type") == "background" }
    assertEquals("16", background.jsonObject.optionalString("shape"))

    val logo = node("figma-10-11")
    assertEquals("layout/box", logo.componentId)
    assertEquals(listOf("size"), logo.modifiers.map { it.jsonObject.optionalString("type") })

    val codes = result.diagnostics.associate { it.figmaNodeId to it.code }
    assertEquals(FigmaImportDiagnostic.UNSUPPORTED_NODE, codes["10:11"])
    assertEquals(FigmaImportDiagnostic.UNMAPPED_INSTANCE, codes["10:12"])
    assertEquals(FigmaImportDiagnostic.HIDDEN, codes["10:14"])
    assertTrue(
      result.diagnostics.single { it.figmaNodeId == "10:12" }.message.contains("Rating bar"),
      "the diagnostic names the component set to map",
    )
  }

  @Test
  fun `every builder node remembers the Figma node it came from`() {
    assertEquals(document.nodes.keys, result.figmaNodeIds.keys)
    assertEquals("10:13", result.figmaNodeIds["figma-10-13-label"])
  }

  @Test
  fun `a stamped node keeps the builder id it was exported with`() {
    val stamped =
      snapshot.copy(
        root =
          snapshot.root.copy(
            stamp = FigmaStamp(designId = "checkout", nodeId = "screen", revision = 7)
          )
      )
    val imported = FigmaSnapshotImporter(catalog, map).import(stamped, designId = "checkout")
    assertEquals(listOf("screen"), UiBuilderReducer.replay(imported.operations).document.roots)
  }

  @Test
  fun `the import is deterministic`() {
    val again = FigmaSnapshotImporter(catalog, map).import(snapshot, designId = "checkout")
    assertEquals(result.operations, again.operations)
  }

  private fun node(id: String) = document.nodes.getValue(id)

  private fun JsonObject.value(name: String): String? =
    (this[name] as? JsonObject)?.get("value")?.jsonPrimitive?.content

  private fun resource(path: String): String =
    checkNotNull(javaClass.getResource(path)) { "missing resource $path" }.readText()
}
