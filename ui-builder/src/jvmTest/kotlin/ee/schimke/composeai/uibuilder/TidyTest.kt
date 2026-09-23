package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The tidy command: every authored dp value in scope moves onto the 4dp grid, as one command.
 *
 * What counts as "authored" is the rule under test — a catalog-declared dp property or a dp field
 * of a layout modifier, holding a plain number. A colour, an enum and a value already on the grid
 * are none of them the command's business, and the shape a number was written in is the shape it
 * keeps.
 */
class TidyTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `off-grid dp values snap and everything else is left alone`() {
    val tidied = reducer.reduce(reducer.initial(document), UiBuilderEditorEvent.Tidy)

    val column = tidied.document.nodes.getValue("tidy-column")
    // 13 sits between 12 and 16 and moves to the nearer one.
    assertEquals(
      "12",
      column.properties.getValue("verticalSpacingDp").jsonObject["value"]?.jsonPrimitive?.content,
    )
    val cell = tidied.document.nodes.getValue("tidy-cell")
    val size =
      cell.modifiers.first { it.jsonObject["type"]?.jsonPrimitive?.content == "size" }.jsonObject
    // 122.5 rounds up onto the grid and keeps its decimal shape; the height was already there.
    assertEquals("124.0", size.getValue("widthDp")?.jsonPrimitive?.content)
    assertEquals("128", size.getValue("heightDp")?.jsonPrimitive?.content)
    // An enum, and the text it reads, are not lengths.
    assertEquals(
      "top",
      column.properties.getValue("verticalArrangement").jsonObject["value"]?.jsonPrimitive?.content,
    )
    assertEquals(
      "accent",
      tidied.document.nodes
        .getValue("tidy-accent")
        .properties
        .getValue("text")
        .jsonObject["value"]
        ?.jsonPrimitive
        ?.content,
    )
  }

  @Test
  fun `a tidy is one command that keeps the selection`() {
    val state = reducer.initial(document, selectedNodeId = "tidy-cell")
    val tidied = reducer.reduce(state, UiBuilderEditorEvent.Tidy)

    assertEquals(state.operationSequence + 1, tidied.operationSequence, "one command, one revision")
    assertTrue(tidied.lastOutcome is CommandOutcome.Accepted, tidied.lastOutcome.toString())
    assertEquals("tidy-cell", tidied.selectedNodeId)
  }

  @Test
  fun `the plan counts what the command moves`() {
    val plan = reducer.tidyPlan(reducer.initial(document))

    // The column's spacing, the cell's width — and nothing else in the fixture is off the grid.
    assertEquals(2, plan.changedValues)
    assertEquals(2, plan.operations.size)
  }

  @Test
  fun `a design already on the grid costs no revision`() {
    val state = reducer.initial(document)
    val tidied = reducer.reduce(state, UiBuilderEditorEvent.Tidy)
    val settled = reducer.reduce(tidied, UiBuilderEditorEvent.Tidy)

    assertEquals(tidied.document, settled.document, "the second tidy changes nothing")
    assertEquals(tidied.operationSequence, settled.operationSequence)
    assertEquals(tidied.document.revision, settled.document.revision)
  }

  @Test
  fun `tidying a selection covers its subtree and nothing beyond it`() {
    val tidied =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = "tidy-cell"),
        UiBuilderEditorEvent.Tidy,
      )

    // The cell's width moved onto the grid; the column's spacing, outside the selection's
    // subtree, did not.
    assertEquals(
      "13",
      tidied.document.nodes
        .getValue("tidy-column")
        .properties
        .getValue("verticalSpacingDp")
        .jsonObject["value"]
        ?.jsonPrimitive
        ?.content,
    )
    assertEquals(
      "124.0",
      tidied.document.nodes
        .getValue("tidy-cell")
        .modifiers
        .first { it.jsonObject["type"]?.jsonPrimitive?.content == "size" }
        .jsonObject
        .getValue("widthDp")
        ?.jsonPrimitive
        ?.content,
    )
  }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "tidy-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Tidy fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 320, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {
                  "spacing": {"type": "number", "initialValue": 16}
                }
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": null,
                "node": {
                  "id": "tidy-column",
                  "componentId": "layout/column",
                  "properties": {
                    "verticalSpacingDp": {"type": "float", "value": 13},
                    "verticalArrangement": {"type": "enum", "value": "top"}
                  }
                }
              },
              {
                "operationId": "cell",
                "type": "insertNode",
                "parent": {"nodeId": "tidy-column", "slot": "children"},
                "node": {
                  "id": "tidy-cell",
                  "componentId": "layout/box",
                  "modifiers": [
                    {"type": "size", "widthDp": 122.5, "heightDp": 128}
                  ]
                }
              },
              {
                "operationId": "accent",
                "type": "insertNode",
                "parent": {"nodeId": "tidy-column", "slot": "children"},
                "afterNodeId": "tidy-cell",
                "node": {
                  "id": "tidy-accent",
                  "componentId": "m3/text",
                  "properties": {
                    "text": {"type": "string", "value": "accent"}
                  }
                }
              }
            ]
          }
          """
            .trimIndent()
        )
        .jsonObject
  }
}
