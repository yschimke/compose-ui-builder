package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The path above the canvas, driven through the real editor.
 *
 * `selectionPath` is pinned by [CanvasDropPlanTest]; this one asks what only the composed bar can
 * answer: every rung from the root to the leaf is on screen, and pressing one re-roots the
 * selection there — which is what makes the path a way back up and not a caption.
 */
@OptIn(ExperimentalTestApi::class)
class SelectionBreadcrumbTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `the selection's path names every rung, and a rung pressed re-roots the selection`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme { UiBuilderEditor(document, catalog, initialSelectedNodeId = "plan-n1") }
      }
      waitForIdle()

      // The rungs above the canvas, root to leaf: the leaf is the selection, its ancestors are
      // pressable. The leaf's own text is on the canvas too, so it is counted, not singular.
      onNodeWithText("Scaffold").assertExists()
      onNodeWithText("Column").assertExists()
      onNodeWithText("Box").assertExists()

      // Pressing the column rung re-roots the selection: the rungs below it leave the bar, and
      // the tight editor that follows the selection now follows the column.
      onNodeWithText("Column").performClick()
      waitForIdle()
      onNodeWithText("Box").assertDoesNotExist()
      onNodeWithText("Column · layout/column").assertExists()
    }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "selection-breadcrumb-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Breadcrumb fixture",
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
                "stateVariables": {}
              },
              {
                "operationId": "scaffold",
                "type": "insertNode",
                "parent": null,
                "node": {"id": "plan-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "plan-scaffold", "slot": "content"},
                "node": {"id": "plan-column", "componentId": "layout/column"}
              },
              {
                "operationId": "a",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "node": {
                  "id": "plan-a",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "A"}}
                }
              },
              {
                "operationId": "box",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "afterNodeId": "plan-a",
                "node": {"id": "plan-box", "componentId": "layout/box"}
              },
              {
                "operationId": "n1",
                "type": "insertNode",
                "parent": {"nodeId": "plan-box", "slot": "children"},
                "node": {
                  "id": "plan-n1",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "N1"}}
                }
              },
              {
                "operationId": "c",
                "type": "insertNode",
                "parent": {"nodeId": "plan-column", "slot": "children"},
                "afterNodeId": "plan-box",
                "node": {
                  "id": "plan-c",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "C"}}
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
