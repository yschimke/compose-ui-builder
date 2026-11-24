package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.CatalogDocumentFormat
import ee.schimke.composeai.uibuilder.export.DOCUMENT_INSPECTION_TAGS_KEY
import ee.schimke.composeai.uibuilder.export.KobentoComponentJsonExporter
import ee.schimke.composeai.uibuilder.export.KobentoDocumentFormat
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.BrowserPreviewCapabilityV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import ee.schimke.composeai.uibuilder.protocol.ExportEncodingV1
import ee.schimke.composeai.uibuilder.protocol.ExportFormatV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Kobento catalog is data this build has never heard of: a `ui-builder.json` captured from
 * `yschimke/kobento-catalog`, which generates it from Kobento's component library, with every
 * component a builtin because none has a Kotlin call site. A design authored against it validates
 * in the runtime and exports as Kobento component JSON. See `docs/design/UI_BUILDER_KOBENTO.md`.
 */
class KobentoCatalogTest {
  private val fixtures: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder") }
      .first { File(it, "kobento-published-v1.json").isFile }

  private val published =
    Json.parseToJsonElement(File(fixtures, "kobento-published-v1.json").readText()).jsonObject

  private val composed =
    assertIs<PublishedUiBuilderCatalog.Result.Composed>(
        PublishedUiBuilderCatalog.compose(
          File(fixtures, "kobento-published-v1.json").readText(),
          null,
          ExportCapabilitiesV1.Builder().build(),
        ),
      )
      .catalog

  @Test
  fun `every published component is served, with its params and slots`() {
    // A library component's display name is its Kobento name with spaces ("Chart 3D" for Chart3D);
    // the two format builtins, the document root and text, are not library components.
    val names =
      published
        .getValue("statusSemantics")
        .jsonObject
        .getValue("builtins")
        .jsonObject
        .filterKeys { it != "kobento/document" && it != "kobento/text" }
        .values
        .map { it.jsonObject.getValue("displayName").jsonPrimitive.content.replace(" ", "") }
    assertEquals(96, names.size)
    val served = composed.components.map { it.componentId }.toSet()
    for (name in names) {
      val id = "kobento/" + name.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), "-").lowercase()
      assertTrue(id in served, "$name is not served as $id")
      assertEquals(name, KobentoComponentJsonExporter.componentName(id))
    }
    assertTrue("kobento/document" in served && "kobento/text" in served)

    val button = composed.components.single { it.componentId == "kobento/button" }
    assertEquals(
      listOf("label", "style", "color", "surface", "enabled", "fill"),
      button.properties.map { it.name },
    )
    assertTrue(button.properties.single { it.name == "label" }.required)
    assertEquals(listOf("icon"), button.slots.map { it.name })
    assertEquals(
      "Scaffold",
      composed.components.single { it.componentId == "kobento/document" }.role,
    )
  }

  @Test
  fun `a Kobento design validates in the runtime and exports as component JSON`() {
    val executor =
      fixtureCatalogExecutor(
        catalogSystemIds = setOf("kobento"),
        published = mapOf("kobento" to composed),
      )
    val catalog = executor.listCatalogs().single { it.benchmark.catalogSystemId == "kobento" }
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    // Pinned to what this executor serves, as a design created against it would be.
    val pinned =
      design.toDesignDocumentV1().copy(catalogPin = assertNotNull(executor.reference(catalog)))
    assertNull(executor.validate(pinned, catalog))

    val emitted =
      assertIs<KobentoComponentJsonExporter.Result.Emitted>(
        KobentoComponentJsonExporter.export(
          design,
          KobentoComponentJsonExporter.Options(componentSets = listOf("square")),
        ),
      )
    val golden = File(fixtures, "kobento-export-sample.json")
    if (System.getenv("UPDATE_KOBENTO_GOLDEN") != null) golden.writeText(emitted.source + "\n")
    assertEquals(golden.readText(), emitted.source + "\n")
  }

  @Test
  fun `what Kobento JSON cannot say is refused, not dropped`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val gauge = design.nodes.getValue("gauge")
    val broken =
      design.copy(
        nodes =
          design.nodes +
            ("gauge" to
              gauge.copy(
                properties =
                  Json.parseToJsonElement("""{"value": {"type": "state", "variable": "nope"}}""")
                    .jsonObject,
              )),
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(broken),
      )
    assertEquals(
      listOf("nodes.gauge.properties.value: state nope is not a declared bool, int or float"),
      refused.reasons,
    )
  }

  @Test
  fun `a series is declared as a float array and passed by name`() {
    val design =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema": "compose-ui-builder-document/v1-candidate", "id": "bars", "title": "Bars",
          "revision": 1, "catalogPin": {}, "stateVariables": {}, "environment": {},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "kobento/document", "slots": {"content": ["bars-1"]}},
            "bars-1": {"id": "bars-1", "componentId": "kobento/bar-chart", "properties": {"values":
              {"type": "list", "values": [{"type": "float", "value": 3}, {"type": "float", "value": 5}]}}}
          }}""",
      )
    val options =
      KobentoComponentJsonExporter.Options()
        .withCatalog(
          Json.parseToJsonElement("""{"seriesParams": {"kobento/bar-chart": ["values"]}}""")
            .jsonObject,
        )
    val emitted =
      assertIs<KobentoComponentJsonExporter.Result.Emitted>(
          KobentoComponentJsonExporter.export(design, options),
        )
        .source
    val compact = Json.parseToJsonElement(emitted).toString()
    assertTrue(""""floatArrays":{"series_bars_1_values":[3,5]}""" in compact, compact)
    assertTrue(""""BarChart":{"values":"@series_bars_1_values"}""" in compact, compact)
    // Without the catalog's word, a list is written in place.
    val inline =
      (KobentoComponentJsonExporter.export(design) as KobentoComponentJsonExporter.Result.Emitted)
        .source
    assertTrue(""""values":[3,5]""" in Json.parseToJsonElement(inline).toString())
  }

  @Test
  fun `a series inside each item of a list param is declared and passed by name`() {
    val design =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema": "compose-ui-builder-document/v1-candidate", "id": "grouped", "title": "Grouped",
          "revision": 1, "catalogPin": {}, "stateVariables": {}, "environment": {},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "kobento/document", "slots": {"content": ["g"]}},
            "g": {"id": "g", "componentId": "kobento/grouped-bars", "properties": {"series":
              {"type": "list", "values": [
                {"type": "object", "fields": {"name": {"type": "string", "value": "A"},
                  "values": {"type": "list", "values": [{"type": "float", "value": 3}, {"type": "float", "value": 5}]}}},
                {"type": "object", "fields": {"name": {"type": "string", "value": "B"},
                  "values": {"type": "list", "values": [{"type": "float", "value": 4}]}}}]}}}
          }}""",
      )
    val options =
      KobentoComponentJsonExporter.Options()
        .withCatalog(
          Json.parseToJsonElement(
              """{"seriesParams": {"kobento/grouped-bars": ["series[].values"]}}""",
            )
            .jsonObject,
        )
    val compact =
      Json.parseToJsonElement(
          assertIs<KobentoComponentJsonExporter.Result.Emitted>(
              KobentoComponentJsonExporter.export(design, options),
            )
            .source,
        )
        .toString()
    assertTrue(
      """"floatArrays":{"series_g_series_0_values":[3,5],"series_g_series_1_values":[4]}""" in
        compact,
      compact,
    )
    assertTrue(
      """"series":[{"name":"A","values":"@series_g_series_0_values"},{"name":"B","values":"@series_g_series_1_values"}]""" in
        compact,
      compact,
    )
  }

  @Test
  fun `a list of objects is written as Kobento's list of items`() {
    val design =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema": "compose-ui-builder-document/v1-candidate", "id": "rings", "title": "Rings",
          "revision": 1, "catalogPin": {}, "stateVariables": {}, "environment": {},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "kobento/document", "slots": {"content": ["rings"]}},
            "rings": {"id": "rings", "componentId": "kobento/activity-rings", "properties": {"rings":
              {"type": "list", "values": [{"type": "object", "fields": {
                "label": {"type": "string", "value": "Move"},
                "value": {"type": "float", "value": 420}}}]}}}
          }}""",
      )
    val emitted =
      assertIs<KobentoComponentJsonExporter.Result.Emitted>(
          KobentoComponentJsonExporter.export(design),
        )
        .source
    val compact = Json.parseToJsonElement(emitted).toString()
    assertTrue(""""rings":[{"label":"Move","value":420}]""" in compact, compact)
  }

  @Test
  fun `a box's content alignment and a list param are written as Kobento's`() {
    fun design(alignment: String) =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema": "compose-ui-builder-document/v1-candidate", "id": "box", "title": "Box",
          "revision": 1, "catalogPin": {}, "stateVariables": {}, "environment": {},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "kobento/document", "slots": {"content": ["frame"]}},
            "frame": {"id": "frame", "componentId": "layout/box",
              "properties": {"contentAlignment": {"type": "string", "value": "$alignment"}},
              "slots": {"children": ["tabs"]}},
            "tabs": {"id": "tabs", "componentId": "kobento/tabs", "properties": {"labels":
              {"type": "list", "values": [{"type": "string", "value": "Day"}, {"type": "string", "value": "Week"}]}}}
          }}""",
      )
    val emitted =
      assertIs<KobentoComponentJsonExporter.Result.Emitted>(
          KobentoComponentJsonExporter.export(design("bottomEnd")),
        )
        .source
    val compact = Json.parseToJsonElement(emitted).toString()
    assertTrue(
      """{"box":{"verticalAlignment":"bottom","horizontalAlignment":"end"""" in compact,
      compact,
    )
    assertTrue(""""Tabs":{"labels":["Day","Week"]}""" in compact, compact)
    val centred =
      Json.parseToJsonElement(
          (KobentoComponentJsonExporter.export(design("center"))
              as KobentoComponentJsonExporter.Result.Emitted)
            .source,
        )
        .toString()
    assertTrue(""""verticalAlignment":"center","horizontalAlignment":"center"""" in centred)
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(design("middle")),
      )
    assertTrue(
      refused.reasons.single().startsWith("nodes.frame.properties.contentAlignment: expected "),
      refused.reasons.toString(),
    )
  }

  @Test
  fun `nullable state is refused, not exported as zero`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val nullable =
      design.copy(
        stateVariables =
          kotlinx.serialization.json.JsonObject(
            design.stateVariables +
              ("target" to
                Json.parseToJsonElement(
                    """{"type": "value", "valueType": "float", "nullable": true, "initialValue": null}""",
                  )
                  .jsonObject),
          ),
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(nullable),
      )
    assertTrue(
      "stateVariables.target: nullable state has no Kobento variable" in refused.reasons,
      refused.reasons.toString(),
    )
  }

  @Test
  fun `state on a static param and a state comparison are refused`() {
    val staticParams =
      Json.parseToJsonElement(File(fixtures, "kobento-published-v1.json").readText())
        .jsonObject
        .getValue("statusSemantics")
        .jsonObject
        .getValue("kobento")
        .jsonObject
        .getValue("staticParams")
        .jsonObject
        .mapValues { (_, names) -> names.jsonArray.map { it.jsonPrimitive.content }.toSet() }
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val gauge = design.nodes.getValue("gauge")
    val warmer = design.nodes.getValue("warmer")
    val properties = { node: kotlinx.serialization.json.JsonObject, extra: String ->
      Json.parseToJsonElement(
          node.toString().dropLast(1) + (if (node.isEmpty()) "" else ",") + extra + "}",
        )
        .jsonObject
    }
    val broken =
      design.copy(
        nodes =
          design.nodes +
            mapOf(
              "gauge" to
                gauge.copy(
                  properties =
                    properties(
                      gauge.properties,
                      """"size": {"type": "state", "variable": "target"}""",
                    ),
                ),
              "warmer" to
                warmer.copy(
                  properties =
                    properties(
                      warmer.properties,
                      """"fill": {"type": "stateEquals", "variable": "heating", "value": true}""",
                    ),
                ),
            ),
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(
          broken,
          KobentoComponentJsonExporter.Options(staticParams = staticParams),
        ),
      )
    assertEquals(
      listOf(
        "nodes.gauge.properties.size: a static Kobento param cannot follow state",
        "nodes.warmer.properties.fill: stateEquals values are not lowered to Kobento yet",
      ),
      refused.reasons,
    )
  }

  @Test
  fun `a system read and a computed increment are refused, not flattened`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val gauge = design.nodes.getValue("gauge")
    val warmer = design.nodes.getValue("warmer")
    val broken =
      design.copy(
        nodes =
          design.nodes +
            mapOf(
              "gauge" to
                gauge.copy(
                  properties =
                    kotlinx.serialization.json.JsonObject(
                      gauge.properties +
                        ("value" to
                          Json.parseToJsonElement("""{"type": "system", "value": "time.hour"}""")),
                    ),
                ),
              "warmer" to
                warmer.copy(
                  eventBindings =
                    Json.parseToJsonElement(
                        """{"click": [{"type": "increment", "variable": "target",
                          "amount": {"type": "formula", "value": "@target * 2"}}]}""",
                      )
                      .jsonObject,
                ),
            ),
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(broken),
      )
    assertEquals(
      listOf(
        "nodes.gauge.properties.value: system values are not lowered to Kobento yet",
        "nodes.warmer.eventBindings.click[0].amount: only a literal number is lowered to Kobento",
      ),
      refused.reasons,
    )
  }

  @Test
  fun `a catalog that publishes its definitions compiles a design locally, tags and all`() {
    val policy =
      Json.parseToJsonElement(
          """{"localCompile": {"definitions": "kobento/definitions.json",
               "theme": "kobento/theme.json", "compiler": "kobento/json2rc.mjs"}}""",
        )
        .jsonObject
    assertNull(KobentoDocumentFormat.localCompilation(JsonObject(emptyMap())))
    val compilation = assertNotNull(KobentoDocumentFormat.localCompilation(policy))
    assertEquals(listOf("kobento/definitions.json", "kobento/theme.json"), compilation.files)
    assertEquals("kobento/json2rc.mjs", compilation.compiler)
    val design =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema": "compose-ui-builder-document/v1-candidate", "id": "local", "title": "Local",
          "revision": 1, "catalogPin": {}, "stateVariables": {},
          "environment": {"widthDp": 200, "heightDp": 100, "${DOCUMENT_INSPECTION_TAGS_KEY}": true},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "kobento/document", "slots": {"content": ["hi"]}},
            "hi": {"id": "hi", "componentId": "kobento/text", "properties": {"value": {"type": "string", "value": "Hi"}}}
          }}""",
      )
    val files =
      mapOf(
        "kobento/definitions.json" to """{"components": {}}""",
        "kobento/theme.json" to
          """{"numbers": {}, "colors": {"background": {"light": "#FFFFFFFF", "dark": "#FF000000"}}}""",
      )
    val result = compilation.expand(design, files)
    val expanded = assertIs<CatalogDocumentFormat.Result.Emitted>(result, result.toString())
    // Plain Remote Compose: the theme colour the document reads is declared, the tags are kept.
    assertTrue(
      """"background":{"light":"#FFFFFFFF","dark":"#FF000000"}""" in expanded.content,
      expanded.content,
    )
    assertTrue("uib-node:hi" in expanded.content, expanded.content)
    assertIs<CatalogDocumentFormat.Result.Refused>(compilation.expand(design, emptyMap()))
  }

  @Test
  fun `an icon's shard is asked for on demand and its symbol drawn as a path`() {
    val policy =
      Json.parseToJsonElement(
          """{"localCompile": {"definitions": "kobento/definitions.json",
               "theme": "kobento/theme.json", "compiler": "kobento/json2rc.mjs",
               "icons": "kobento/icons/{variant}/{shard}.json"}}""",
        )
        .jsonObject
    val compilation = assertNotNull(KobentoDocumentFormat.localCompilation(policy))
    // Three styles, filled or not, a shard per first character.
    assertEquals(216, compilation.onDemand.size)
    assertTrue("kobento/icons/rounded-filled/h.json" in compilation.onDemand)
    val design =
      Json.decodeFromString(
        UiBuilderDocument.serializer(),
        """{"schema": "compose-ui-builder-document/v1-candidate", "id": "icon", "title": "Icon",
          "revision": 1, "catalogPin": {}, "stateVariables": {},
          "environment": {"widthDp": 100, "heightDp": 100},
          "roots": ["screen"],
          "nodes": {
            "screen": {"id": "screen", "componentId": "kobento/document", "slots": {"content": ["home"]}},
            "home": {"id": "home", "componentId": "kobento/icon", "properties": {"name": {"type": "string", "value": "home"}}}
          }}""",
      )
    val files =
      mutableMapOf(
        "kobento/definitions.json" to
          """{"components": {"Icon": {"params": {"name": {"type": "string", "required": true, "static": true},
               "style": {"type": "enum", "default": "outlined", "values": ["outlined", "rounded", "sharp"]},
               "filled": {"type": "bool", "default": false, "static": true}},
             "body": {"canvas": {"commands": [{"drawPath": {"path": {"${'$'}icon":
               {"name": "@name", "style": "@style", "filled": "@filled"}}}}]}}}}}""",
        "kobento/theme.json" to """{"numbers": {}, "colors": {}}""",
      )
    // The host's loop: what the expansion read and lacked, it fetches, and expands again.
    val read = mutableListOf<String>()
    val recording =
      object : AbstractMap<String, String>() {
        override val entries
          get() = files.entries

        override fun get(key: String): String? = files[key].also { read += key }
      }
    assertIs<CatalogDocumentFormat.Result.Refused>(compilation.expand(design, recording))
    assertEquals(
      listOf("kobento/icons/outlined/h.json"),
      read.filter { it in compilation.onDemand },
    )
    files["kobento/icons/outlined/h.json"] =
      """{"home": "M160-120v-480l320-240 320 240v480H160Z"}"""
    val result = compilation.expand(design, files)
    val expanded = assertIs<CatalogDocumentFormat.Result.Emitted>(result, result.toString())
    assertTrue(
      "\"drawPath\":{\"path\":\"${'$'}paths.icon_home_outlined\"}" in expanded.content,
      expanded.content,
    )
    assertTrue(
      "\"icon_home_outlined\":[{\"type\":\"moveTo\",\"x\":160,\"y\":840}" in expanded.content,
      expanded.content,
    )
  }

  @Test
  fun `a canvas's export carries each node's id, and a design's own export does not`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    fun tags(document: UiBuilderDocument): List<String> {
      val emitted =
        assertIs<KobentoComponentJsonExporter.Result.Emitted>(
          KobentoComponentJsonExporter.export(document),
        )
      return Regex("\"contentDescription\": \"uib-node:([^\"]+)\"")
        .findAll(emitted.source)
        .map { it.groupValues[1] }
        .toList()
    }
    assertEquals(emptyList(), tags(design))
    val tagged =
      design.copy(
        environment =
          JsonObject(
            design.environment + (DOCUMENT_INSPECTION_TAGS_KEY to JsonPrimitive(true)),
          ),
      )
    assertEquals(design.nodes.keys.sorted(), tags(tagged).sorted())
    // A call takes no modifiers, so its tag is on a box around it; a layout carries its own.
    val root =
      Json.parseToJsonElement(
          (KobentoComponentJsonExporter.export(tagged)
              as KobentoComponentJsonExporter.Result.Emitted)
            .source,
        )
        .jsonObject
        .getValue("root")
        .toString()
    assertTrue(
      root.contains(
        "{\"box\":{\"modifiers\":[{\"semantics\":{\"contentDescription\":\"uib-node:gauge\"}}],\"children\":[{\"Gauge\"",
      ),
    )
  }

  @Test
  fun `an RTL design or an environment background is refused`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val environment =
      Json.parseToJsonElement(
          """{"widthDp": 360, "heightDp": 320, "density": 1.0, "layoutDirection": "rtl",
            "background": "#FF000000"}""",
        )
        .jsonObject
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(design.copy(environment = environment)),
      )
    assertEquals(
      listOf(
        "environment.layoutDirection: RTL lowering is not available",
        "environment.background: background lowering is not available",
      ),
      refused.reasons,
    )
  }

  @Test
  fun `an int a Kobento float cannot hold exactly is refused`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val warmer = design.nodes.getValue("warmer")
    val broken =
      design.copy(
        stateVariables =
          kotlinx.serialization.json.JsonObject(
            design.stateVariables +
              ("count" to
                Json.parseToJsonElement(
                    """{"type": "value", "valueType": "int", "initialValue": 16777217}""",
                  )
                  .jsonObject) +
              ("small" to
                Json.parseToJsonElement(
                    """{"type": "value", "valueType": "int", "initialValue": 0}""",
                  )
                  .jsonObject),
          ),
        nodes =
          design.nodes +
            ("warmer" to
              warmer.copy(
                eventBindings =
                  Json.parseToJsonElement(
                      """{"click": [{"type": "set", "variable": "small", "value": -16777217}]}""",
                    )
                    .jsonObject,
              )),
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(broken),
      )
    assertEquals(
      listOf(
        "stateVariables.count: an int beyond ±16777216 is not exact in a Kobento float",
        "nodes.warmer.eventBindings.click[0]: an int beyond ±16777216 is not exact in a Kobento float",
      ),
      refused.reasons,
    )
  }

  @Test
  fun `a modifier field the lowering cannot write is refused`() {
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    val column = design.nodes.getValue("column")
    val shaped =
      column.copy(
        modifiers =
          Json.parseToJsonElement(
              """[{"type": "background", "color": {"type": "color", "value": "#FF336699"},
                "shape": "medium"}]""",
            )
            .jsonArray,
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(
          design.copy(nodes = design.nodes + ("column" to shaped)),
        ),
      )
    assertEquals(
      listOf("nodes.column.modifiers[0].shape: modifier field lowering is not available"),
      refused.reasons,
    )
  }

  @Test
  fun `a click on a component with no onClick is refused, read from the catalog`() {
    val kobento =
      Json.parseToJsonElement(File(fixtures, "kobento-published-v1.json").readText())
        .jsonObject
        .getValue("statusSemantics")
        .jsonObject
        .getValue("kobento")
        .jsonObject
    val options = KobentoComponentJsonExporter.Options().withCatalog(kobento)
    val design = Json.decodeFromString(UiBuilderDocument.serializer(), SAMPLE)
    // The sample's Button click is allowed by the same catalog data.
    assertIs<KobentoComponentJsonExporter.Result.Emitted>(
      KobentoComponentJsonExporter.export(design, options),
    )
    val gauge = design.nodes.getValue("gauge")
    val clicked =
      gauge.copy(
        eventBindings =
          Json.parseToJsonElement(
              """{"click": [{"type": "hostAction", "name": "gauge.tapped"}]}""",
            )
            .jsonObject,
      )
    val refused =
      assertIs<KobentoComponentJsonExporter.Result.Refused>(
        KobentoComponentJsonExporter.export(
          design.copy(nodes = design.nodes + ("gauge" to clicked)),
          options,
        ),
      )
    assertEquals(
      listOf("nodes.gauge.eventBindings.click: kobento/gauge takes no onClick"),
      refused.reasons,
    )
  }

  @Test
  fun `every seed kobento-catalog publishes opens as a valid design and exports`() {
    // The delivery branch as captured (scripts/capture-catalog-cutover-fixtures.sh kobento): the
    // published policy and the seed documents its `templates` name.
    val policy =
      Json.parseToJsonElement(
        checkNotNull(CatalogCutoverFixtures.text("kobento/ui-builder.json.gz")),
      )
    val served =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
          PublishedUiBuilderCatalog.compose(
            policy.toString(),
            null,
            ExportCapabilitiesV1.Builder().build(),
          ),
        )
        .catalog
    val executor =
      fixtureCatalogExecutor(
        catalogSystemIds = setOf("kobento"),
        published = mapOf("kobento" to served),
      )
    val catalog = executor.listCatalogs().single { it.benchmark.catalogSystemId == "kobento" }
    val reference = assertNotNull(executor.reference(catalog))
    val kobento = policy.jsonObject.getValue("statusSemantics").jsonObject
    val templates = kobento.getValue("templates").jsonArray.map { it.jsonPrimitive.content }
    // The blank screen first: a host opens the first template when none is named.
    assertEquals(
      listOf(
        "ui-builder/designs/kobento-screen.json",
        "ui-builder/designs/kobento-thermostat.json",
      ),
      templates,
    )
    val options =
      KobentoComponentJsonExporter.Options().withCatalog(kobento.getValue("kobento").jsonObject)
    for (path in templates) {
      val seed =
        Json.decodeFromString(
          UiBuilderDocument.serializer(),
          checkNotNull(
            CatalogCutoverFixtures.text("kobento/designs/" + path.substringAfterLast('/')),
          ) {
            "$path is not captured"
          },
        )
      assertNull(
        executor.validate(seed.toDesignDocumentV1().copy(catalogPin = reference), catalog),
        path,
      )
      assertIs<KobentoComponentJsonExporter.Result.Emitted>(
        KobentoComponentJsonExporter.export(seed, options),
        path,
      )
    }
  }

  @Test
  fun `a kobento catalog advertises and serves kobento json in place of compose`() {
    val policy =
      Json.parseToJsonElement(
        checkNotNull(CatalogCutoverFixtures.text("kobento/ui-builder.json.gz")),
      )
    val served =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
          PublishedUiBuilderCatalog.compose(
            policy.toString(),
            null,
            ExportCapabilitiesV1.Builder().build(),
          ),
        )
        .catalog
    val renderer =
      object : UiBuilderRenderPort {
        override val supportsSvg = false

        override fun renderPng(request: UiBuilderRenderRequest): ByteArray =
          error("a Kobento export renders nothing")

        override fun renderSvg(request: UiBuilderRenderRequest): ByteArray =
          error("a Kobento export renders nothing")

        override fun close() = Unit
      }
    val compose = UiBuilderExportExecutor { error("a Kobento export never asks for Compose") }
    ProductionUiBuilderExportExecutor(renderer, compose).use { exporter ->
      assertTrue(exporter.capabilities.kobentoJson)
      val executor =
        fixtureCatalogExecutor(
          catalogSystemIds = setOf("kobento"),
          published = mapOf("kobento" to served),
          exportCapabilities = exporter.capabilities,
        )
      val catalog = executor.listCatalogs().single { it.benchmark.catalogSystemId == "kobento" }
      with(catalog.exportCapabilities) {
        assertTrue(kobentoJson)
        assertFalse(composeCode)
        assertFalse(remoteJson)
        assertFalse(remoteDocument)
      }
      assertTrue(catalog.supports(ExportFormatV1.KOBENTO_JSON))
      assertFalse(catalog.supports(ExportFormatV1.COMPOSE))

      val seed =
        Json.decodeFromString(
            UiBuilderDocument.serializer(),
            checkNotNull(CatalogCutoverFixtures.text("kobento/designs/kobento-thermostat.json")),
          )
          .toDesignDocumentV1()
          .copy(catalogPin = assertNotNull(executor.reference(catalog)))
      val artifact =
        exporter.export(
          RevisionPinnedUiBuilderExport(
            actor = AuthenticatedUiBuilderActor("owner"),
            designId = seed.id,
            revision = seed.revision,
            documentHash = "unused",
            document = seed,
            catalog = catalog,
            format = ExportFormatV1.KOBENTO_JSON,
          ),
        )
      assertEquals(ExportFormatV1.KOBENTO_JSON, artifact.format)
      assertEquals("application/json", artifact.mediaType)
      assertEquals(ExportEncodingV1.UTF8, artifact.encoding)
      assertEquals(artifactDigest(artifact), artifact.contentDigest)
      val screen = Json.parseToJsonElement(artifact.content).jsonObject
      assertTrue(screen.isNotEmpty(), artifact.content)
    }
  }

  @Test
  fun `a host that compiles kobento json offers and previews its rc, and only then`() {
    val policy =
      Json.parseToJsonElement(
        checkNotNull(CatalogCutoverFixtures.text("kobento/ui-builder.json.gz")),
      )
    val served =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
          PublishedUiBuilderCatalog.compose(
            policy.toString(),
            null,
            ExportCapabilitiesV1.Builder().build(),
          ),
        )
        .catalog
    fun catalog(compilers: Set<String>) =
      CurrentM3UiBuilderCatalogExecutor.Builder()
        .apply {
          catalogSystemIds = setOf("kobento")
          published = mapOf("kobento" to served)
          exportCapabilities = ExportCapabilitiesV1.Builder().apply { kobentoJson = true }.build()
          documentCompilers = compilers
        }
        .build()
        .listCatalogs()
        .single { it.benchmark.catalogSystemId == "kobento" }

    val compiled = catalog(setOf(KobentoDocumentFormat.id))
    assertTrue(compiled.exportCapabilities.remoteDocument)
    // Not behind the Remote Compose authoring flag: Kobento writes these bytes, not this build.
    assertTrue(compiled.supports(ExportFormatV1.RC))
    assertTrue(compiled.supports(ExportFormatV1.KOBENTO_JSON))

    // The canvas cannot draw Kobento's components, so its Preview plays the compiled document.
    assertEquals(
      BrowserPreviewCapabilityV1.REMOTE_COMPOSE_DOCUMENT_RENDERER,
      compiled.browserPreview?.renderer,
    )
    assertEquals(ExportFormatV1.RC, compiled.browserPreview?.format)

    val plain = catalog(emptySet())
    assertFalse(plain.exportCapabilities.remoteDocument)
    assertFalse(plain.supports(ExportFormatV1.RC))
    assertNull(plain.browserPreview)
  }

  @Test
  fun `a kobento catalog keeps the builder vocabulary its stored designs may hold`() {
    val policy =
      Json.parseToJsonElement(
        checkNotNull(CatalogCutoverFixtures.text("kobento/ui-builder.json.gz")),
      )
    val served =
      assertIs<PublishedUiBuilderCatalog.Result.Composed>(
          PublishedUiBuilderCatalog.compose(
            policy.toString(),
            null,
            ExportCapabilitiesV1.Builder().build(),
          ),
        )
        .catalog
    val catalog =
      fixtureCatalogExecutor(
          catalogSystemIds = setOf("kobento"),
          published = mapOf("kobento" to served),
        )
        .listCatalogs()
        .single { it.benchmark.catalogSystemId == "kobento" }
    // The palette offers only what Kobento JSON writes (the editor's paletteHiddenComponentIds),
    // but a design saved holding a drawing must still validate, open and be fixed.
    val ids = catalog.components.map { it.componentId }.toSet()
    assertTrue(KobentoComponentJsonExporter.BUILDER_LAYOUTS.all { it in ids })
    assertTrue("draw/rect" in ids, ids.toString())
    assertFalse(KobentoDocumentFormat.writes("draw/rect"))
    assertTrue(KobentoDocumentFormat.writes("kobento/button"))
  }

  @Test
  fun `a catalog without a kobento policy is never offered kobento json`() {
    val renderer =
      object : UiBuilderRenderPort {
        override val supportsSvg = false

        override fun renderPng(request: UiBuilderRenderRequest): ByteArray = ByteArray(0)

        override fun renderSvg(request: UiBuilderRenderRequest): ByteArray = ByteArray(0)

        override fun close() = Unit
      }
    ProductionUiBuilderExportExecutor(renderer, UiBuilderExportExecutor { error("unused") }).use {
      exporter ->
      val catalog =
        fixtureCatalogExecutor(exportCapabilities = exporter.capabilities).listCatalogs().single()
      assertFalse(catalog.exportCapabilities.kobentoJson)
      assertFalse(catalog.supports(ExportFormatV1.KOBENTO_JSON))
    }
  }

  private companion object {
    /** A settings card: a title, a switch and a button driving state, and a gauge reading it. */
    val SAMPLE =
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "kobento-sample",
        "title": "Thermostat",
        "revision": 0,
        "catalogPin": {
          "systemId": "kobento",
          "catalogRevision": "candidate",
          "capabilityDigest": "candidate",
          "nativeRuntimeId": "candidate"
        },
        "environment": {
          "widthDp": 360,
          "heightDp": 320,
          "density": 1.0,
          "theme": "dark",
          "locale": "en-GB",
          "fontScale": 1.0,
          "layoutDirection": "ltr"
        },
        "stateVariables": {
          "heating": { "type": "value", "valueType": "bool", "initialValue": true, "persistence": "preview" },
          "target": { "type": "value", "valueType": "float", "initialValue": 21, "persistence": "preview" }
        },
        "roots": ["screen"],
        "nodes": {
          "screen": {
            "id": "screen",
            "componentId": "kobento/document",
            "modifiers": [
              { "type": "padding", "startDp": 16, "topDp": 16, "endDp": 16, "bottomDp": 16 }
            ],
            "slots": { "content": ["column"] }
          },
          "column": {
            "id": "column",
            "componentId": "layout/column",
            "properties": {
              "verticalSpacingDp": { "type": "float", "value": 12 },
              "horizontalAlignment": { "type": "enum", "value": "center" }
            },
            "modifiers": [{ "type": "fillMaxWidth" }],
            "slots": { "children": ["title", "gauge", "controls"] }
          },
          "title": {
            "id": "title",
            "componentId": "kobento/text",
            "properties": {
              "value": { "type": "string", "value": "Living room" },
              "fontSize": { "type": "float", "value": 20 },
              "color": { "type": "color", "value": "@colors.onSurface" }
            }
          },
          "gauge": {
            "id": "gauge",
            "componentId": "kobento/gauge",
            "properties": {
              "value": { "type": "state", "variable": "target" },
              "min": { "type": "float", "value": 10 },
              "max": { "type": "float", "value": 30 },
              "label": { "type": "string", "value": "Target °C" }
            }
          },
          "controls": {
            "id": "controls",
            "componentId": "layout/row",
            "properties": {
              "horizontalSpacingDp": { "type": "float", "value": 12 },
              "verticalAlignment": { "type": "enum", "value": "center" }
            },
            "slots": { "children": ["switch", "warmer"] }
          },
          "switch": {
            "id": "switch",
            "componentId": "kobento/switch",
            "properties": {
              "checked": { "type": "state", "variable": "heating" },
              "label": { "type": "string", "value": "Heating" }
            }
          },
          "warmer": {
            "id": "warmer",
            "componentId": "kobento/button",
            "properties": {
              "label": { "type": "string", "value": "Warmer" },
              "style": { "type": "enum", "value": "outlined" }
            },
            "eventBindings": {
              "click": [
                { "type": "increment", "variable": "target", "amount": 1 },
                { "type": "hostAction", "name": "thermostat.target" }
              ]
            }
          }
        }
      }
      """
        .trimIndent()
  }
}
