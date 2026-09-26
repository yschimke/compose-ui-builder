package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.EditorCanvasView
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The device view of a Wear screen: the real round watch, and its list popped out beside it.
 *
 * The extent draws a Wear screen as a stadium — the list as a Column, the clock over the top cap —
 * which is the only way every row can be reached, and nothing like the watch. The device view is
 * the watch: the port's real `AppScaffold`, `ScreenScaffold` and `TransformingLazyColumn` in a
 * round frame, with the list that the selection is in drawn unrolled beside it. These pin what
 * [CanvasDeviceViewTest] pins for a phone, on the watch.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasWearDeviceViewTest {
  private val screen =
    UiBuilderReducer.replay(Json.parseToJsonElement(wearListFixture(ROWS)).jsonObject).document

  @Test
  fun `the device view keeps a Wear screen at the watch where the extent is a stadium`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var view by mutableStateOf(EditorCanvasView.Extent)
      var measured = 0 to 0
      setContent {
        WearCatalogAdapters {
          DeviceViewCanvasHost(screen, view = view, onMetrics = { w, h -> measured = w to h })
        }
      }
      waitForIdle()
      val extent = measured

      view = EditorCanvasView.Device
      waitForIdle()

      assertTrue(extent.second > WATCH_DP * 2, "the stadium holds every row ($extent)")
      assertEquals(WATCH_DP to WATCH_DP, measured, "the device view is the watch, no taller")
    }

  /** The square frame's corners are outside the watch, and nothing may be drawn in them. */
  @Test
  fun `the watch frame is round`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var frame = Rect.Zero
      setContent {
        WearCatalogAdapters {
          DeviceViewCanvasHost(screen, view = EditorCanvasView.Device, onFrame = { frame = it })
        }
      }
      waitForIdle()

      val pixels = onRoot().captureToImage().toPixelMap()
      val corner = pixels[frame.left.toInt() + 2, frame.top.toInt() + 2]
      val centre = pixels[frame.center.x.toInt(), frame.center.y.toInt()]
      assertEquals(0f, corner.alpha, "the frame's corner is outside the watch ($corner)")
      assertEquals(1f, centre.alpha, "the watch itself is drawn ($centre)")
    }

  @Test
  fun `selecting a row far down the watch list scrolls it onto the watch`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected by mutableStateOf<String?>(null)
      var snapshot: UiBuilderInspectionSnapshot? = null
      var frame = Rect.Zero
      setContent {
        WearCatalogAdapters {
          DeviceViewCanvasHost(
            screen,
            view = EditorCanvasView.Device,
            selectedNodeId = selected,
            onInspection = { snapshot = it },
            onFrame = { frame = it },
          )
        }
      }
      waitForIdle()
      assertNull(bounds(snapshot, "row-$DEEP_ROW"), "row $DEEP_ROW starts below the watch")

      selected = "row-$DEEP_ROW"
      waitForIdle()

      val box = assertNotNull(bounds(snapshot, "row-$DEEP_ROW"), "the row was scrolled to")
      assertTrue(
        box.y >= frame.top - 0.5f && box.bottom <= frame.bottom + 0.5f,
        "row $DEEP_ROW is on the watch ($box in $frame)",
      )
    }

  @Test
  fun `a row selected on the watch pops the whole list out, and a click there selects`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected by mutableStateOf<String?>("row-1")
      setContent {
        WearCatalogAdapters {
          DeviceViewCanvasHost(
            screen,
            view = EditorCanvasView.Device,
            selectedNodeId = selected,
            onSelected = { selected = it },
          )
        }
      }
      waitForIdle()

      (1..ROWS).forEach { popOutNode("Item $it").assertExists() }
      popOutNode("Item $CLICKED_ROW").performClick()
      waitForIdle()

      assertTrue(
        selected == "row-$CLICKED_ROW" || selected == "row-$CLICKED_ROW-label",
        "a click in the pop-out selects that row ($selected)",
      )
      popOutNode("Item $ROWS").assertExists()
    }

  @Test
  fun `the wheel scrolls the watch list`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        WearCatalogAdapters {
          DeviceViewCanvasHost(
            screen,
            view = EditorCanvasView.Device,
            onInspection = { snapshot = it },
          )
        }
      }
      waitForIdle()
      val before = assertNotNull(bounds(snapshot, "row-1")).y

      onRoot().performMouseInput {
        moveTo(Offset(WATCH_DP / 2f, WATCH_DP / 2f))
        repeat(5) { scroll(3f) }
      }
      waitForIdle()

      val after = bounds(snapshot, "row-1")?.y
      assertTrue(
        after == null || after < before,
        "the list moved under the wheel ($before, $after)",
      )
    }

  private fun SemanticsNodeInteractionsProvider.popOutNode(text: String) =
    onNode(hasText(text) and hasAnyAncestor(hasContentDescription("Unrolled", substring = true)))

  private fun bounds(snapshot: UiBuilderInspectionSnapshot?, nodeId: String) =
    snapshot?.nodes?.firstOrNull { it.nodeId == nodeId }?.bounds

  private companion object {
    const val WATCH_DP = 192
    const val ROWS = 12
    const val DEEP_ROW = 10
    const val CLICKED_ROW = 8

    /** A round screen over a transforming list of [rows] buttons, several watches tall. */
    fun wearListFixture(rows: Int): String {
      val items =
        (1..rows).joinToString(",") { index ->
          val after = if (index == 1) "" else "\"afterNodeId\": \"row-${index - 1}\","
          """
          {"operationId": "row$index", "type": "insertNode",
           "parent": {"nodeId": "list", "slot": "items"}, $after
           "node": {"id": "row-$index", "componentId": "wear-m3/button",
                    "properties": {"variant": {"type": "enum", "value": "filled"}},
                    "modifiers": [{"type": "fillMaxWidth"}]}},
          {"operationId": "row$index-label", "type": "insertNode",
           "parent": {"nodeId": "row-$index", "slot": "content"},
           "node": {"id": "row-$index-label", "componentId": "wear-m3/text",
                    "properties": {"text": {"type": "string", "value": "Item $index"}}}}
          """
        }
      return """
      {
        "documentSchema": "compose-ui-builder-document/v1-candidate",
        "designId": "canvas-wear-device",
        "operations": [
          {
            "operationId": "create",
            "type": "createDesign",
            "title": "Wear device view fixture",
            "catalogPin": {
              "systemId": "wear-m3",
              "catalogRevision": "candidate",
              "capabilityDigest": "candidate",
              "nativeRuntimeId": "candidate"
            },
            "environment": {
              "widthDp": $WATCH_DP, "heightDp": $WATCH_DP, "density": 1.0, "theme": "dark",
              "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
              "layoutDirection": "ltr", "windowPosture": "flat",
              "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
              "animations": "settled", "networkAccess": false
            },
            "stateVariables": {}
          },
          {"operationId": "screen", "type": "insertNode", "parent": null,
           "node": {"id": "screen", "componentId": "wear-m3/screen-scaffold",
                    "properties": {"timeText": {"type": "string", "value": "10:10"}}}},
          {"operationId": "list", "type": "insertNode",
           "parent": {"nodeId": "screen", "slot": "content"},
           "node": {"id": "list", "componentId": "wear-m3/transforming-lazy-column",
                    "properties": {"verticalSpacingDp": {"type": "float", "value": 4}},
                    "modifiers": [{"type": "fillMaxSize"}]}},
          $items
        ]
      }
      """
    }
  }
}
