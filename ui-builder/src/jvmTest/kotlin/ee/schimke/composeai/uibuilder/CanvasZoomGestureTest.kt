package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import ee.schimke.composeai.uibuilder.editor.anchorDrift
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
  fun `the drift is where the design point landed relative to the hand`() {
    // The frame is at (100, 50) and the design point (100, 50) is drawn at 3x, so it lands at
    // (400, 200). The hand is at (300, 200): 100px right of it, level vertically.
    assertEquals(
      Offset(100f, 0f),
      anchorDrift(Offset(100f, 50f), Offset(100f, 50f), 3f, Offset(300f, 200f)),
    )
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
  fun `a design centred on one axis keeps the point under the pointer as it starts to overflow`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var snapshot: UiBuilderInspectionSnapshot? = null
      // 640px tall in a 700px workspace: centred with a 30px margin, which one notch (2.25x, 720px)
      // takes away while opening 20px of scroll. The point under y can be kept there only while the
      // scroll it needs, 0.125y - 33.75, fits in that 20px — y from 270 to 430. At 350 it needs
      // 10px;
      // reading the point as `scroll + y` forgets the margin and asks for 43.75, clamped to 20.
      setContent {
        MaterialTheme {
          // Centred, as both editor layouts draw it.
          Host(initial = 2f, centred = true, onInspection = { snapshot = it }) {}
        }
      }
      waitForIdle()
      val pointer = Offset(450f, 350f)
      fun fractionY(): Float {
        val frame =
          assertNotNull(snapshot?.nodes?.firstOrNull { it.nodeId == "plan-scaffold" }?.bounds)
        return (pointer.y - frame.y) / frame.height
      }
      val before = fractionY()
      onRoot().performMultiModalInput {
        key { keyDown(Key.CtrlLeft) }
        mouse {
          moveTo(pointer)
          scroll(-1f)
        }
        key { keyUp(Key.CtrlLeft) }
      }
      waitForIdle()
      // In pixels, not as a fraction: forgetting the centring margin is a few pixels off here,
      // which a fraction of the whole design would round away.
      val frame =
        assertNotNull(snapshot?.nodes?.firstOrNull { it.nodeId == "plan-scaffold" }?.bounds)
      assertEquals(pointer.y, frame.y + before * frame.height, 1.5f)
    }

  @Test
  fun `a pinch that also moves carries the design point with the fingers`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent { MaterialTheme { Host(initial = 2f, onInspection = { snapshot = it }) {} } }
      waitForIdle()
      fun frame() =
        assertNotNull(snapshot?.nodes?.firstOrNull { it.nodeId == "plan-scaffold" }?.bounds)
      // Fingers 200px apart around (400, 300), spreading to 300px apart around (400, 200): a 1.5x
      // zoom to 3x while the pinch rises 100px. The design point under the start must end under
      // the end — the pan is part of the gesture, not something the zoom's scroll overwrites.
      val start = frame()
      val fraction = (300f - start.y) / start.height
      onRoot().performTouchInput {
        down(0, Offset(300f, 300f))
        down(1, Offset(500f, 300f))
        for (step in 1..10) {
          val t = step / 10f
          moveTo(0, Offset(300f - 50f * t, 300f - 100f * t), delayMillis = 16)
          moveTo(1, Offset(500f + 50f * t, 300f - 100f * t), delayMillis = 16)
        }
        up(0)
        up(1)
      }
      waitForIdle()
      val end = frame()
      assertEquals(200f, end.y + fraction * end.height, 3f)
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
    centred: Boolean = false,
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
      contentAlignment = if (centred) Alignment.Center else Alignment.TopStart,
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
