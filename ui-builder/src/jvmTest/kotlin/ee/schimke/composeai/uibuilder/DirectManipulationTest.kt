package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.optionalStringValue
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The editor's direct-manipulation gestures, driven through the real pointer on the real editor:
 * the resize handles and size chips on a selection, and the palette's touch rules — a vertical
 * swipe scrolls the shelf, a sideways one or a long press picks the component up.
 */
@OptIn(ExperimentalTestApi::class)
class DirectManipulationTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  private fun UiBuilderEditorState?.modifiersOf(nodeId: String): List<String> =
    assertNotNull(this).document.nodes.getValue(nodeId).modifiers.mapNotNull {
      (it as? JsonObject)?.optionalStringValue("type")
    }

  @Test
  fun `dragging the end handle to the parent's edge fills the width, and Hug takes it back`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var state: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialCanvasZoom = 1f,
            onStateChanged = { state = it },
          )
        }
      }
      waitForIdle()
      onNodeWithText("A").performClick()
      waitForIdle()
      assertEquals("dm-a", state?.selectedNodeId)

      // Well past the column's edge: the edge snaps to the parent, which is Fill.
      onNodeWithContentDescription("Resize width").performMouseInput {
        moveTo(center)
        press(MouseButton.Primary)
        moveTo(center + Offset(40f, 0f))
        moveTo(center + Offset(400f, 0f))
        release(MouseButton.Primary)
      }
      waitForIdle()
      assertEquals(listOf("fillMaxWidth"), state.modifiersOf("dm-a"))

      // The hover editor's chip is the other half of the same decision.
      onNodeWithContentDescription("Width hugs content").performClick()
      waitForIdle()
      assertEquals(emptyList(), state.modifiersOf("dm-a"))
    }

  @Test
  fun `pulling an edge in writes a fixed size in whole dp`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var state: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialCanvasZoom = 1f,
            onStateChanged = { state = it },
          )
        }
      }
      waitForIdle()
      onNodeWithText("Wide label for sizing").performClick()
      waitForIdle()
      assertEquals("dm-b", state?.selectedNodeId)

      onNodeWithContentDescription("Resize width").performTouchInput {
        down(center)
        moveTo(center - Offset(20f, 0f))
        moveTo(center - Offset(40f, 0f))
        up()
      }
      waitForIdle()
      val width = assertNotNull(state).document.nodes.getValue("dm-b").modifiers.single().jsonObject
      assertEquals("width", width.optionalStringValue("type"))
      assertTrue(width.getValue("widthDp").toString().toInt() > 0, "a positive whole dp: $width")
    }

  @Test
  fun `a vertical touch swipe on a palette tile scrolls rather than picks up`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var state: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { state = it },
          )
        }
      }
      waitForIdle()
      val before = assertNotNull(state).document.nodes.size
      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()
      val dropAt = onNodeWithText("A").fetchSemanticsNode().boundsInRoot.center
      val tile = onNodeWithContentDescription("Drag Text")
      val origin = tile.fetchSemanticsNode().boundsInRoot.topLeft

      // Up and down: the shelf's, not the component's — even when it ends over the canvas.
      tile.performTouchInput {
        down(center)
        moveTo(center + Offset(0f, 40f))
        moveTo(Offset(dropAt.x - origin.x, dropAt.y - origin.y))
        up()
      }
      waitForIdle()
      assertEquals(before, state?.document?.nodes?.size, "a vertical swipe inserted nothing")

      // A long press, then anywhere: picked up.
      tile.performTouchInput {
        down(center)
        advanceEventTime(1_000)
        moveTo(center + Offset(0f, 30f))
        moveTo(Offset(dropAt.x - origin.x, dropAt.y - origin.y))
        up()
      }
      waitForIdle()
      assertEquals(before + 1, state?.document?.nodes?.size, "a held press picked it up")
      assertTrue(onAllNodesWithText("New text").fetchSemanticsNodes().isNotEmpty())
    }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "direct-manipulation-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Direct manipulation fixture",
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
                "node": {"id": "dm-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "dm-scaffold", "slot": "content"},
                "node": {
                  "id": "dm-column",
                  "componentId": "layout/column",
                  "modifiers": [{"type": "fillMaxWidth"}]
                }
              },
              {
                "operationId": "a",
                "type": "insertNode",
                "parent": {"nodeId": "dm-column", "slot": "children"},
                "node": {
                  "id": "dm-a",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "A"}}
                }
              },
              {
                "operationId": "b",
                "type": "insertNode",
                "parent": {"nodeId": "dm-column", "slot": "children"},
                "afterNodeId": "dm-a",
                "node": {
                  "id": "dm-b",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "Wide label for sizing"}}
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
