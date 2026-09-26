package ee.schimke.composeai.uibuilder.export

import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class A2uiComposeExporterTest {

  private fun wrapped(type: String, value: String) = buildJsonObject {
    put("type", type)
    put("value", value)
  }

  private fun document(
    nodes: List<UiBuilderNode>,
    state: JsonObject = JsonObject(emptyMap()),
    title: String = "Booking",
  ) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1",
      id = "booking",
      title = title,
      revision = 1,
      catalogPin =
        buildJsonObject {
          put("systemId", A2uiDocumentExporter.CATALOG_SYSTEM_ID)
          put("catalogRevision", "test")
          put("capabilityDigest", "test")
          put("nativeRuntimeId", "test")
        },
      environment =
        buildJsonObject {
          put("widthDp", 412)
          put("heightDp", 915)
          put("density", 2.625)
          put("theme", "light")
          put("locale", "en-US")
          put("fontScale", 1.0)
          put("layoutDirection", "ltr")
        },
      stateVariables = state,
      roots = listOf(nodes.first().id),
      nodes = nodes.associateBy { it.id },
    )

  private val booking =
    listOf(
      UiBuilderNode("column", "a2ui/Column", slots = mapOf("children" to listOf("title", "book"))),
      UiBuilderNode(
        "title",
        "a2ui/Text",
        properties =
          buildJsonObject {
            put("text", wrapped("string", "Weekend in Lisbon"))
            put("variant", wrapped("enum", "h3"))
          },
      ),
      UiBuilderNode(
        "book",
        "a2ui/Button",
        properties =
          buildJsonObject {
            put(
              "action",
              buildJsonObject {
                put("type", "object")
                putJsonObject("fields") {
                  putJsonObject("event") {
                    put("type", "object")
                    putJsonObject("fields") { put("name", wrapped("string", "book")) }
                  }
                }
              },
            )
          },
        slots = mapOf("child" to listOf("label")),
      ),
      UiBuilderNode(
        "label",
        "a2ui/Text",
        properties = buildJsonObject { put("text", wrapped("string", "Book")) },
      ),
    )

  @Test
  fun `a design becomes a composable that sends its own payload to the processor`() {
    val result =
      assertIs<A2uiComposeExporter.Result.Emitted>(
        A2uiComposeExporter.export(document(booking), packageName = "com.example.a2ui")
      )
    val source = result.source

    assertEquals("BookingScreen", result.composableName)
    assertTrue(source.contains("package com.example.a2ui\n"), source)
    assertTrue(source.contains("@Composable\nfun BookingScreen(modifier: Modifier = Modifier) {"))
    listOf(
        "import androidx.a2ui.compose.ui.A2uiMessageProcessor",
        "import androidx.a2ui.model.catalog.functions.A2uiLocaleProvider",
        "import androidx.a2ui.model.protocol.A2uiComponentPayload",
        "import androidx.a2ui.model.protocol.A2uiCreateSurfaceMessage",
        "import androidx.a2ui.model.protocol.A2uiUpdateComponentsMessage",
        "import androidx.compose.material3.a2ui.A2uiSurface",
        "import androidx.compose.material3.a2ui.catalog.MaterialA2uiBasicCatalogV1Defaults",
        "import androidx.compose.material3.a2ui.catalog.materialA2uiBasicCatalogV1",
        "materialA2uiBasicCatalogV1(",
        "MaterialA2uiBasicCatalogV1Defaults.image {",
        "MaterialA2uiBasicCatalogV1Defaults.video {",
        "MaterialA2uiBasicCatalogV1Defaults.audioPlayer {",
        "localeProvider = A2uiLocaleProvider.Default,",
        "A2uiMessageProcessor(catalogs = listOf(catalog))",
        "launch { processor.collectMessages() }",
        "processor.processMessage(A2uiCreateSurfaceMessage(\"booking\", catalog.id))",
        "A2uiSurface(surfaceModel = surface, modifier = modifier)",
      )
      .forEach { assertTrue(it in source, "missing `$it` in\n$source") }
    // No state, so no data model message and no import for one.
    assertTrue("A2uiUpdateDataModelMessage" !in source, source)

    // The same lowering as the JSON export: root renamed, slots as ids, wrappers as bare values.
    val payloads =
      Regex("""A2uiComponentPayload\(\s*id = "([^"]+)",\s*type = "([^"]+)"""")
        .findAll(source)
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()
    assertEquals(
      listOf("root" to "Column", "title" to "Text", "book" to "Button", "label" to "Text"),
      payloads,
    )
    assertTrue("properties = mapOf(\"children\" to listOf(\"title\", \"book\"))" in source, source)
    assertTrue(
      "properties = mapOf(\"text\" to \"Weekend in Lisbon\", \"variant\" to \"h3\")" in source,
      source,
    )
    assertTrue("\"action\" to mapOf(\"event\" to mapOf(\"name\" to \"book\"))" in source, source)
    assertTrue("\"child\" to \"label\"" in source, source)
  }

  @Test
  fun `declared state becomes the surface's data model and a reference becomes a path`() {
    val state = buildJsonObject {
      putJsonObject("fullName") {
        put("type", "string")
        put("initialValue", "Ada")
      }
    }
    val nodes =
      listOf(
        UiBuilderNode(
          "field",
          "a2ui/TextField",
          properties =
            buildJsonObject {
              put("label", wrapped("string", "Name"))
              putJsonObject("value") {
                put("type", "state")
                put("variable", "fullName")
              }
            },
        )
      )
    val source =
      assertIs<A2uiComposeExporter.Result.Emitted>(
          A2uiComposeExporter.export(document(nodes, state))
        )
        .source

    assertTrue("import androidx.a2ui.model.protocol.A2uiUpdateDataModelMessage" in source)
    assertTrue("mapOf(\"fullName\" to \"Ada\")" in source, source)
    assertTrue("\"value\" to mapOf(\"path\" to \"/fullName\")" in source, source)
    // The data model is sent between the surface and its components, the order an agent streams.
    assertTrue(
      source.indexOf("A2uiCreateSurfaceMessage(") < source.indexOf("A2uiUpdateDataModelMessage(") &&
        source.indexOf("A2uiUpdateDataModelMessage(") <
          source.indexOf("A2uiUpdateComponentsMessage(")
    )
    // A snippet has no package line.
    assertTrue(!source.contains("\npackage "), source)
  }

  @Test
  fun `a design the JSON export refuses is refused here for the same reasons`() {
    val nodes =
      listOf(
        UiBuilderNode(
          "column",
          "a2ui/Column",
          modifiers = buildJsonArray { add(buildJsonObject { put("type", "padding") }) },
          slots = mapOf("children" to listOf("box")),
        ),
        UiBuilderNode("box", "layout/box"),
      )
    val doc = document(nodes)
    val compose = assertIs<A2uiComposeExporter.Result.Refused>(A2uiComposeExporter.export(doc))
    val json = assertIs<A2uiDocumentExporter.Result.Refused>(A2uiDocumentExporter.export(doc))
    assertEquals(json.reasons, compose.reasons)
    assertTrue(compose.reasons.any { "modifiers" in it })
    assertTrue(compose.reasons.any { "`layout/box` is not an A2UI component" in it })
  }

  @Test
  fun `strings are escaped so the literal is the value`() {
    val nasty = "Say \"hi\" to \$name\\\n\ttab\r\u0001"
    assertEquals(
      "\"Say \\\"hi\\\" to \\\$name\\\\\\n\\ttab\\r\\u0001\"",
      A2uiComposeExporter.kotlinString(nasty),
    )
    // Round-trips through a JSON string reader, whose escapes are Kotlin's for these characters.
    assertEquals(
      nasty,
      Json.decodeFromString<String>(A2uiComposeExporter.kotlinString(nasty).replace("\\$", "$")),
    )
  }

  @Test
  fun `values are written as the Kotlin the decoder would have produced`() {
    assertEquals("null", A2uiComposeExporter.literal(JsonNull))
    assertEquals("true", A2uiComposeExporter.literal(JsonPrimitive(true)))
    assertEquals("12", A2uiComposeExporter.literal(JsonPrimitive(12)))
    assertEquals("1.5", A2uiComposeExporter.literal(JsonPrimitive(1.5)))
    assertEquals("5000000000L", A2uiComposeExporter.literal(JsonPrimitive(5_000_000_000L)))
    assertEquals("emptyMap<String, Any?>()", A2uiComposeExporter.literal(JsonObject(emptyMap())))
    assertEquals("emptyList<Any?>()", A2uiComposeExporter.literal(JsonArray(emptyList())))
    assertEquals(
      "listOf(\"a\", 1, null)",
      A2uiComposeExporter.literal(
        JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive(1), JsonNull))
      ),
    )
    // A value too wide for one line breaks out one entry per line, each closing at its own indent.
    val options = buildJsonArray {
      listOf("One", "Two", "Three").forEach { label ->
        add(
          buildJsonObject {
            put("label", "Option $label")
            put("value", label.lowercase())
          }
        )
      }
    }
    assertEquals(
      "listOf(\n" +
        "  mapOf(\"label\" to \"Option One\", \"value\" to \"one\"),\n" +
        "  mapOf(\"label\" to \"Option Two\", \"value\" to \"two\"),\n" +
        "  mapOf(\"label\" to \"Option Three\", \"value\" to \"three\"),\n" +
        ")",
      A2uiComposeExporter.literal(options),
    )
  }

  @Test
  fun `the record-free lane answers an A2UI catalog with this Kotlin`() {
    val doc = document(booking)
    val generated =
      assertIs<RecordFreeExport.Generated.Emitted>(
        RecordFreeExport.generate(doc, UiBuilderCatalogPlatform.A2UI, "com.example")
      )
    assertEquals("BookingScreen", generated.composableName)
    assertTrue("A2uiComponentPayload(" in generated.source)

    // The saved document takes the same road, and the host's gate says it applies.
    val saved: DesignDocumentV1 = doc.toDesignDocumentV1()
    assertTrue(RecordFreeExport.applies(saved, UiBuilderCatalogPlatform.A2UI))
    assertEquals(
      generated,
      RecordFreeExport.generate(saved, UiBuilderCatalogPlatform.A2UI, "com.example"),
    )
    // No other platform claims an A2UI design.
    assertEquals(null, RecordFreeExport.generate(doc, UiBuilderCatalogPlatform.MOBILE))
  }

  @Test
  fun `the A2UI seed validates as a design and exports both ways`() {
    val seed =
      a2uiUiBuilderDocument(
        designId = "seed",
        catalogPin = buildJsonObject { put("systemId", A2uiDocumentExporter.CATALOG_SYSTEM_ID) },
        environment = JsonObject(emptyMap()),
      )
    assertEquals(listOf("a2ui-column"), seed.roots)
    assertEquals("a2ui/Column", seed.nodes.getValue("a2ui-column").componentId)
    assertIs<A2uiDocumentExporter.Result.Emitted>(A2uiDocumentExporter.export(seed))
    assertIs<A2uiComposeExporter.Result.Emitted>(A2uiComposeExporter.export(seed))
    assertEquals(
      setOf(UiBuilderNewDesignSeed.A2UI_TEMPLATE),
      UiBuilderNewDesignSeed.templateIds(A2uiDocumentExporter.CATALOG_SYSTEM_ID),
    )
  }
}
