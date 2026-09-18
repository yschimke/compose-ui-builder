package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A palette drop on the empty ground adds beside the design.
 *
 * The pointer over the workspace but not over the design is a place nothing can be inserted *into*;
 * the honest answer to a release there is the panel's own add-beside — a top-level item on the
 * board — rather than a swallow. Driven through the real palette drag on the real editor, so the
 * whole path is under test: the gesture, the empty-ground decision, the board prelude and the
 * insert, and the selection that follows the inserted node.
 */
@OptIn(ExperimentalTestApi::class)
class BesideDropTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `a palette drop on the empty ground adds beside the design`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      var frameBounds = Rect.Zero
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
            onCanvasBoundsChanged = { frameBounds = it },
          )
        }
      }
      waitForIdle()

      // Filter the palette down to the Text row: the catalog is a long lazy list, and the search
      // field is the editor's own answer to that.
      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()

      // Drop on the canvas pane's own ground: just below the centred design frame — over the
      // workspace, and over nothing insertable.
      val row = onDragRow("Drag Text")
      val rowOrigin = row.fetchSemanticsNode().boundsInRoot.topLeft
      val dropAt = Offset(frameBounds.left, frameBounds.bottom + 80f)
      row.performTouchInput {
        down(center)
        moveTo(center + Offset(24f, 24f))
        moveTo(Offset(dropAt.x - rowOrigin.x, dropAt.y - rowOrigin.y))
        up()
      }
      waitForIdle()

      // The inserted text is on the canvas, named "New text" by the component's own default, and
      // the selection followed it — the bar above the canvas names the new layer.
      assertTrue(
        onAllNodesWithText("New text").fetchSemanticsNodes().isNotEmpty(),
        "the inserted text is on the canvas",
      )
      assertTrue(
        onAllNodesWithText("New text · m3/text").fetchSemanticsNodes().isNotEmpty(),
        "the selection followed the inserted node",
      )
    }

  @Test
  fun `a palette drop inside the design still inserts into a slot`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      var frameBounds = Rect.Zero
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
            onCanvasBoundsChanged = { frameBounds = it },
          )
        }
      }
      waitForIdle()

      // Filter the palette to the Text row, then drop it into the frame's centre: a compatible
      // slot, so the ordinary drop is unchanged.
      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()
      val row = onDragRow("Drag Text")
      val rowOrigin = row.fetchSemanticsNode().boundsInRoot.topLeft
      // A slot's hit region is the union of its measured children, so the drop aims at the
      // rendered "A" rather than the middle of the column around it.
      val dropAt = onNodeWithText("A").fetchSemanticsNode().boundsInRoot.center
      row.performTouchInput {
        down(center)
        moveTo(center + Offset(24f, 24f))
        moveTo(Offset(dropAt.x - rowOrigin.x, dropAt.y - rowOrigin.y))
        up()
      }
      waitForIdle()

      assertTrue(
        onAllNodesWithText("New text").fetchSemanticsNodes().isNotEmpty(),
        "the inserted text is on the canvas",
      )
    }

  /** The palette row whose thumbnail carries the drag named by [contentDescription]. */
  private fun androidx.compose.ui.test.ComposeUiTest.onDragRow(
    contentDescription: String
  ): SemanticsNodeInteraction = onNodeWithContentDescription(contentDescription)

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "beside-drop-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Beside drop fixture",
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
                "node": {"id": "beside-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "beside-scaffold", "slot": "content"},
                "node": {"id": "beside-column", "componentId": "layout/column"}
              },
              {
                "operationId": "a",
                "type": "insertNode",
                "parent": {"nodeId": "beside-column", "slot": "children"},
                "node": {
                  "id": "beside-a",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "A"}}
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
