package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The placeholder, on the real canvas: an empty container invites a component while one is being
 * dragged, and is left alone otherwise — at rest an empty box is usually meant to be empty.
 */
@OptIn(ExperimentalTestApi::class)
class SlotPlaceholderUiTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `an empty container invites a drop only while a component is dragged`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var frameBounds = Rect.Zero
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
            onCanvasBoundsChanged = { frameBounds = it },
          )
        }
      }
      waitForIdle()

      // At rest the empty root box is drawn as the design has it, with no invitation over it, and
      // the panel's own statement of where an Add lands still names the layer.
      onNodeWithContentDescription("Drop into children").assertDoesNotExist()
      onNodeWithText("Adds into Box › children").assertExists()

      // Pick up the Text row and hold it over the design: now the box invites the drop.
      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()
      val row = onNodeWithContentDescription("Drag Text")
      val rowOrigin = row.fetchSemanticsNode().boundsInRoot.topLeft
      val over = frameBounds.center
      row.performMouseInput {
        moveTo(center)
        press(MouseButton.Primary)
        moveTo(center + Offset(24f, 24f))
        moveTo(Offset(over.x - rowOrigin.x, over.y - rowOrigin.y))
      }
      waitForIdle()
      onNodeWithContentDescription("Drop into children").assertExists()

      // Released into it, the box is filled and the invitation is gone with the drag.
      row.performMouseInput { release(MouseButton.Primary) }
      waitForIdle()
      assertTrue(
        onAllNodesWithText("New text").fetchSemanticsNodes().isNotEmpty(),
        "the dropped text is on the canvas",
      )
      onNodeWithContentDescription("Drop into children").assertDoesNotExist()
    }

  @Test
  fun `a container too small for the slot's name is invited without a clipped label`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var frameBounds = Rect.Zero
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            UiBuilderReducer.replay(SWATCH_FIXTURE.jsonObject).document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
            onCanvasBoundsChanged = { frameBounds = it },
          )
        }
      }
      waitForIdle()

      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()
      val row = onNodeWithContentDescription("Drag Text")
      val rowOrigin = row.fetchSemanticsNode().boundsInRoot.topLeft
      val over = frameBounds.center
      row.performMouseInput {
        moveTo(center)
        press(MouseButton.Primary)
        moveTo(center + Offset(24f, 24f))
        moveTo(Offset(over.x - rowOrigin.x, over.y - rowOrigin.y))
      }
      waitForIdle()

      // Both boxes are invited, but only the panel is labelled: over the 7dp battery cell
      // "children" would clip to a stray "c" in the design, so its dashed region is drawn alone.
      assertEquals(
        1,
        onAllNodesWithContentDescription("Drop into children").fetchSemanticsNodes().size,
        "only the panel carries the slot's name",
      )
      row.performMouseInput { release(MouseButton.Primary) }
      waitForIdle()
    }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "slot-placeholder-ui-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Placeholder UI fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 640, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {
                "operationId": "box",
                "type": "insertNode",
                "parent": null,
                "node": {
                  "id": "root-box",
                  "componentId": "layout/box",
                  "modifiers": [{"type": "fillMaxSize"}]
                }
              }
            ]
          }
          """
            .trimIndent()
        )
        .jsonObject

    private val SWATCH_FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "slot-placeholder-swatch-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Placeholder UI fixture",
                "catalogPin": {
                  "systemId": "m3-catalog",
                  "catalogRevision": "candidate",
                  "capabilityDigest": "candidate",
                  "nativeRuntimeId": "candidate"
                },
                "environment": {
                  "widthDp": 320, "heightDp": 640, "density": 1.0, "theme": "dark",
                  "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
                  "layoutDirection": "ltr", "windowPosture": "flat",
                  "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
                  "animations": "settled", "networkAccess": false
                },
                "stateVariables": {}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": null,
                "node": {
                  "id": "root-column",
                  "componentId": "layout/column",
                  "modifiers": [{"type": "fillMaxSize"}]
                }
              },
              {
                "operationId": "cell",
                "type": "insertNode",
                "parent": {"nodeId": "root-column", "slot": "children"},
                "node": {
                  "id": "cell",
                  "componentId": "layout/box",
                  "modifiers": [
                    {"type": "width", "widthDp": 7},
                    {"type": "height", "heightDp": 14}
                  ]
                }
              },
              {
                "operationId": "panel",
                "type": "insertNode",
                "parent": {"nodeId": "root-column", "slot": "children"},
                "node": {
                  "id": "panel",
                  "componentId": "layout/box",
                  "modifiers": [{"type": "fillMaxWidth"}, {"type": "height", "heightDp": 200}]
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
