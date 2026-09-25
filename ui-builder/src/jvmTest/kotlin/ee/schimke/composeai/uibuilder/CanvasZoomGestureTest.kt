package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.PinnedDesignCanvas
import ee.schimke.composeai.uibuilder.editor.anchoredScroll
import ee.schimke.composeai.uibuilder.editor.wheelZoom
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.reference.ReferenceOverlayState
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Pinch and Ctrl/Cmd + wheel zoom the canvas, through the real pointer on the real canvas; a plain
 * wheel and one finger are left to the scroll.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasZoomGestureTest {
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  @Test
  fun `a wheel notch zooms by the same factor both ways, capped per event`() {
    val zoomedIn = wheelZoom(1f, -1f)
    assertTrue(zoomedIn > 1f)
    assertEquals(1f, wheelZoom(zoomedIn, 1f), 0.0001f)
    // A browser's hundred-pixel notch is one capped step, not a leap to the maximum.
    assertEquals(wheelZoom(1f, -3f), wheelZoom(1f, -100f))
  }

  @Test
  fun `the point under the hand stays under it`() {
    // At 1x, content x 300 is under a pointer at 100 with the scroll at 200. At 2x it is at 600, so
    // the scroll has to be 500 for the pointer to still be over it.
    assertEquals(500f, anchoredScroll(200f, 100f, 1f, 2f))
    assertEquals(0f, anchoredScroll(0f, 100f, 1f, 0.5f))
  }

  @Test
  fun `ctrl and the wheel zoom in, and a plain wheel does not`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var zoom: Float? = 1f
      setContent { MaterialTheme { Host { zoom = it } } }
      waitForIdle()

      onRoot().performMouseInput {
        moveTo(Offset(300f, 300f))
        scroll(-1f)
      }
      waitForIdle()
      assertEquals(1f, zoom, "a plain wheel scrolls the workspace")

      onRoot().performMultiModalInput {
        key { keyDown(Key.CtrlLeft) }
        mouse {
          moveTo(Offset(300f, 300f))
          scroll(-1f)
        }
        key { keyUp(Key.CtrlLeft) }
      }
      waitForIdle()
      assertTrue((zoom ?: 0f) > 1f, "ctrl + wheel up zooms in: $zoom")
    }

  @Test
  fun `the design point under the pointer stays under it as ctrl and the wheel zoom`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var snapshot: UiBuilderInspectionSnapshot? = null
      // Already bigger than the workspace, so there is scroll to anchor with.
      setContent { MaterialTheme { Host(initial = 3f, onInspection = { snapshot = it }) {} } }
      waitForIdle()
      val pointer = Offset(300f, 300f)
      fun fraction(): Offset {
        val frame =
          assertNotNull(snapshot?.nodes?.firstOrNull { it.nodeId == "plan-scaffold" }?.bounds)
        return Offset((pointer.x - frame.x) / frame.width, (pointer.y - frame.y) / frame.height)
      }
      val before = fraction()

      onRoot().performMultiModalInput {
        key { keyDown(Key.CtrlLeft) }
        mouse {
          moveTo(pointer)
          scroll(-1f)
        }
        key { keyUp(Key.CtrlLeft) }
      }
      waitForIdle()
      val after = fraction()
      assertEquals(before.x, after.x, 0.01f)
      assertEquals(before.y, after.y, 0.01f)
    }

  @Test
  fun `two fingers pinching together zoom out`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var zoom: Float? = 2f
      setContent { MaterialTheme { Host(initial = 2f) { zoom = it } } }
      waitForIdle()

      onRoot().performTouchInput {
        pinch(
          start0 = Offset(200f, 300f),
          end0 = Offset(280f, 300f),
          start1 = Offset(500f, 300f),
          end1 = Offset(420f, 300f),
        )
      }
      waitForIdle()
      assertTrue((zoom ?: 2f) < 2f, "a closing pinch zooms out: $zoom")
    }

  @androidx.compose.runtime.Composable
  private fun Host(
    initial: Float = 1f,
    onInspection: ((UiBuilderInspectionSnapshot) -> Unit)? = null,
    onZoom: (Float?) -> Unit,
  ) {
    var zoom by remember { mutableStateOf<Float?>(initial) }
    PinnedDesignCanvas(
      document = document,
      selectedNodeId = null,
      onNodeSelected = {},
      onCanvasMetrics = { _, _, _ -> },
      onCanvasBounds = {},
      dropHovered = false,
      showSelectionOverlay = true,
      moveDragEnabled = true,
      reference = ReferenceOverlayState(mintedIds = 0),
      onMarkDrawn = { _, _ -> },
      onPieceMoved = { _, _, _ -> },
      collaborators = emptyList(),
      commentThreads = emptyList(),
      selectedThreadId = null,
      onCommentThreadSelected = {},
      onInspectionSnapshot = onInspection,
      onInspectionInvalidated = null,
      selectionMenu = { emptyList() },
      hoverEditor = null,
      zoom = zoom,
      onZoomChanged = {
        zoom = it
        onZoom(it)
      },
    )
  }

  private companion object {
    private val FIXTURE =
      Json.parseToJsonElement(
          """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "canvas-move-drag-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Move drag fixture",
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
