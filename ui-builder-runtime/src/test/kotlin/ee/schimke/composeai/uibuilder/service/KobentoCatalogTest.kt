package ee.schimke.composeai.uibuilder.service

import ee.schimke.composeai.uibuilder.export.KobentoComponentJsonExporter
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.toDesignDocumentV1
import ee.schimke.composeai.uibuilder.protocol.ExportCapabilitiesV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Kobento catalog is data this build has never heard of: a `ui-builder.json` generated from a
 * snapshot of Kobento's component library (`scripts/kobento/snapshot-kobento-catalog.py`), with
 * every component a builtin because none has a Kotlin call site. A design authored against it
 * validates in the runtime and exports as Kobento component JSON. See
 * `docs/design/UI_BUILDER_KOBENTO.md`.
 */
class KobentoCatalogTest {
  private val fixtures: File =
    generateSequence(File("").absoluteFile) { it.parentFile }
      .map { File(it, "docs/design/fixtures/ui-builder") }
      .first { File(it, "kobento-published-v1.json").isFile }

  private val snapshot =
    Json.parseToJsonElement(File(fixtures, "kobento-components-snapshot-v1.json").readText())
      .jsonObject

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
  fun `every snapshotted component is served, with its params and slots`() {
    val names =
      snapshot.getValue("components").jsonArray.map {
        it.jsonObject.getValue("name").jsonPrimitive.content
      }
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
