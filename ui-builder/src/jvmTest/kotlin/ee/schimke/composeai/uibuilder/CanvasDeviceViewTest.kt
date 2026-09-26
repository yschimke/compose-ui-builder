package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorCanvasView
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.bottom
import ee.schimke.composeai.uibuilder.renderer.sdk.right
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The canvas at the device's frame, with the selection's scrolling container popped out beside it.
 *
 * The extent draws a long list whole and grows the frame to hold it, which is the right surface
 * until the design is a tablet whose every pane is a list. The device view keeps the frame the
 * device's size and lets the lists scroll; what that costs — rows past the fold nobody can click —
 * is paid back by drawing the selected list again beside the frame, unrolled and editable. These
 * pin the four things that makes true: the frame stays put, the selection is scrolled to, the
 * pop-out holds every row and answers a click, and a row that scrolled away stops answering one.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasDeviceViewTest {
  private val list = replay(listFixture(rows = ROWS))
  private val row = replay(rowFixture(chips = CHIPS))
  private val twoLists = replay(twoListsFixture())
  private val loop = replay(loopFixture())
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )

  @Test
  fun `the device view keeps the frame at its own height where the extent grows`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var view by mutableStateOf(EditorCanvasView.Extent)
      var measured = 0 to 0
      setContent {
        DeviceViewCanvasHost(
          list,
          view = view,
          onMetrics = { width, height -> measured = width to height },
        )
      }
      waitForIdle()
      val extent = measured

      view = EditorCanvasView.Device
      waitForIdle()

      assertTrue(extent.second > FRAME_DP * 2, "the extent should hold every row ($extent)")
      assertEquals(FRAME_DP to FRAME_DP, measured, "the device view is the frame, no taller")
    }

  @Test
  fun `selecting a row far down the list scrolls it into the frame`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected by mutableStateOf<String?>(null)
      var snapshot: UiBuilderInspectionSnapshot? = null
      var frame = Rect.Zero
      setContent {
        DeviceViewCanvasHost(
          list,
          view = EditorCanvasView.Device,
          selectedNodeId = selected,
          onInspection = { snapshot = it },
          onFrame = { frame = it },
        )
      }
      waitForIdle()
      assertNull(bounds(snapshot, "row-$DEEP_ROW"), "row $DEEP_ROW starts below the fold")

      // As the layers panel selects: by id, with nothing under any pointer.
      selected = "row-$DEEP_ROW"
      waitForIdle()

      val box = assertNotNull(bounds(snapshot, "row-$DEEP_ROW"), "the row was scrolled to")
      assertTrue(
        box.x >= frame.left && box.right <= frame.right + 0.5f,
        "row $DEEP_ROW is inside the frame across ($box in $frame)",
      )
      assertTrue(
        box.y >= frame.top - 0.5f && box.bottom <= frame.bottom + 0.5f,
        "row $DEEP_ROW is inside the frame down ($box in $frame)",
      )
    }

  @Test
  fun `a row selected in the device view pops the whole list out, and a click there selects`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected by mutableStateOf<String?>("row-2")
      setContent {
        DeviceViewCanvasHost(
          list,
          view = EditorCanvasView.Device,
          selectedNodeId = selected,
          onSelected = { selected = it },
        )
      }
      waitForIdle()

      (1..ROWS).forEach { index -> popOutNode("Row $index").assertExistsInPopOut("Row $index") }
      // Below the frame's fold, so only the pop-out draws it.
      popOutNode("Row $CLICKED_ROW").performClick()
      waitForIdle()

      assertEquals("row-$CLICKED_ROW", selected, "a click in the pop-out selects that row")
      popOutNode("Row $ROWS").assertExistsInPopOut("the pop-out stays open inside its list")
    }

  @Test
  fun `a lazy row pops out as one row, left to right`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      setContent {
        DeviceViewCanvasHost(row, view = EditorCanvasView.Device, selectedNodeId = "chip-$CHIPS")
      }
      waitForIdle()

      val boxes = (1..CHIPS).map { popOutNode("Chip $it").getUnclippedBoundsInRoot() }
      boxes.zipWithNext().forEach { (before, after) ->
        assertTrue(after.left > before.left, "chips run left to right ($before, $after)")
        assertEquals(before.top.value, after.top.value, 0.5f, "on one line ($before, $after)")
      }
      // Wider than the frame it came out of, which is the point of unrolling it.
      assertTrue(boxes.last().right - boxes.first().left > FRAME_DP.dp, "the row is whole")
    }

  @Test
  fun `a row scrolled out of the frame no longer reports where it was`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        DeviceViewCanvasHost(list, view = EditorCanvasView.Device, onInspection = { snapshot = it })
      }
      waitForIdle()
      assertNotNull(bounds(snapshot, "row-1"), "the first row is on screen to begin with")

      onNode(hasScrollToIndexAction()).performScrollToIndex(ROWS - 1)
      waitForIdle()

      assertNull(bounds(snapshot, "row-1"), "a disposed row answers no press any more")
      assertNotNull(bounds(snapshot, "row-$ROWS"), "the rows now on screen do")
    }

  /**
   * The overlay that selects on the extent is drawn over the design and takes every pointer event,
   * which on the device frame would leave its lists unscrollable by the very wheel that is the
   * reason to look at them.
   */
  @Test
  fun `the wheel scrolls the device frame's list`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var snapshot: UiBuilderInspectionSnapshot? = null
      setContent {
        DeviceViewCanvasHost(list, view = EditorCanvasView.Device, onInspection = { snapshot = it })
      }
      waitForIdle()
      val before = assertNotNull(bounds(snapshot, "row-1")).y

      onRoot().performMouseInput {
        moveTo(Offset(FRAME_DP / 2f, FRAME_DP / 2f))
        repeat(5) { scroll(3f) }
      }
      waitForIdle()

      val after = bounds(snapshot, "row-1")?.y
      assertTrue(
        after == null || after < before,
        "the list moved under the wheel ($before, $after)",
      )
    }

  /**
   * Moving the selection from one list to another in the same revision re-roots the pop-out on a
   * different container. Its geometry has to start again with it: the boxes the first list's rows
   * left behind sat under the second list's rows and, being smaller, won the click.
   */
  @Test
  fun `a pop-out re-rooted on another list forgets the first list's boxes`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected by mutableStateOf<String?>("a-2")
      setContent {
        DeviceViewCanvasHost(
          twoLists,
          view = EditorCanvasView.Device,
          selectedNodeId = selected,
          onSelected = { selected = it },
        )
      }
      waitForIdle()
      popOutNode("A2").assertExistsInPopOut("the first list is out")

      selected = "b-1"
      waitForIdle()
      // At the row's left edge, which is where the first list's shorter label used to be.
      val target = popOutNode("Bravo 2").getUnclippedBoundsInRoot()
      onRoot().performTouchInput {
        click(
          Offset(
            (target.left.value + 3f) * density,
            (target.top + target.bottom).value / 2f * density,
          )
        )
      }
      waitForIdle()

      assertEquals("b-2", selected, "the click lands on the list that is out now")
    }

  /**
   * A `for-each` draws one authored node several times, and the inspection keeps one box per id —
   * whichever copy measured last. The device frame's click reads every copy's box instead, so any
   * copy selects the node rather than whatever ancestor happened to hold the point.
   */
  @Test
  fun `a click on any copy a loop draws selects its template`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var selected: String? = null
      setContent {
        DeviceViewCanvasHost(
          loop,
          view = EditorCanvasView.Device,
          onSelected = { selected = it },
        )
      }
      waitForIdle()

      // The first of three copies; the last one drawn is two cells further down.
      onRoot().performTouchInput { click(Offset(CELL_DP / 2f * density, CELL_DP / 2f * density)) }
      waitForIdle()

      assertEquals("cell", selected, "the first copy selects the template it is a copy of")
    }

  /** The switch lives in the zoom bar, and only where the device view can be drawn at all. */
  @Test
  fun `the zoom bar switches the editor between the extent and the device frame`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var height = 0
      setContent {
        UiBuilderEditor(
          document = list,
          catalog = catalog,
          openDefaultPreview = false,
          onCanvasMetrics = { _, measured, _ -> height = measured },
        )
      }
      waitForIdle()
      assertTrue(height > FRAME_DP * 2, "the editor opens on the extent ($height)")

      onNodeWithContentDescription("Device view", substring = true).performClick()
      waitForIdle()

      assertEquals(FRAME_DP, height, "the device view draws the frame")
    }

  @Test
  fun `the canvas view is a tool mode that survives an arriving document`() {
    val reducer = UiBuilderEditorReducer(catalog)
    val device =
      reducer.reduce(
        reducer.initial(list),
        UiBuilderEditorEvent.SetCanvasView(EditorCanvasView.Device),
      )

    assertEquals(EditorCanvasView.Device, device.canvasView)
    assertEquals(
      EditorCanvasView.Device,
      reducer.reconciled(device, list.copy(revision = list.revision + 1)).canvasView,
      "an edit rebuilds the state; the view the author chose must not reset with it",
    )
  }

  private fun androidx.compose.ui.test.SemanticsNodeInteractionsProvider.popOutNode(text: String) =
    onNode(hasText(text) and hasAnyAncestor(hasContentDescription("Unrolled", substring = true)))

  private fun SemanticsNodeInteraction.assertExistsInPopOut(message: String) {
    try {
      assertExists()
    } catch (error: AssertionError) {
      throw AssertionError("$message: not drawn in the pop-out", error)
    }
  }

  private fun bounds(snapshot: UiBuilderInspectionSnapshot?, nodeId: String) =
    snapshot?.nodes?.firstOrNull { it.nodeId == nodeId }?.bounds

  internal companion object {
    const val FRAME_DP = 320
    const val ROWS = 30
    const val DEEP_ROW = 27
    const val CLICKED_ROW = 20
    const val CHIPS = 12
    const val CELL_DP = 24

    fun replay(operations: String): UiBuilderDocument =
      UiBuilderReducer.replay(Json.parseToJsonElement(operations).jsonObject).document

    /** A 320dp square frame over a scaffold holding a lazy column of [rows] texts. */
    fun listFixture(rows: Int): String =
      fixture(
        "canvas-device-list",
        """
        {"operationId": "scaffold", "type": "insertNode", "parent": null,
         "node": {"id": "screen", "componentId": "layout/scaffold"}},
        {"operationId": "list", "type": "insertNode",
         "parent": {"nodeId": "screen", "slot": "content"},
         "node": {"id": "list", "componentId": "layout/lazy-column",
                  "modifiers": [{"type": "fillMaxSize"}]}},
        ${children("list", "items", "row", "Row", rows)}
        """,
      )

    /** The same frame over a column holding a lazy row of [chips] texts, wider than the frame. */
    fun rowFixture(chips: Int): String =
      fixture(
        "canvas-device-row",
        """
        {"operationId": "column", "type": "insertNode", "parent": null,
         "node": {"id": "column", "componentId": "layout/column",
                  "modifiers": [{"type": "fillMaxSize"}]}},
        {"operationId": "chips", "type": "insertNode",
         "parent": {"nodeId": "column", "slot": "children"},
         "node": {"id": "chips", "componentId": "layout/lazy-row",
                  "properties": {"horizontalSpacingDp": {"type": "float", "value": 16}}}},
        ${children("chips", "items", "chip", "Chip", chips)}
        """,
      )

    /** Two lists side by side: short labels on the left, longer ones on the right. */
    fun twoListsFixture(): String =
      fixture(
        "canvas-device-two-lists",
        """
        {"operationId": "row", "type": "insertNode", "parent": null,
         "node": {"id": "row", "componentId": "layout/row",
                  "modifiers": [{"type": "fillMaxSize"}]}},
        {"operationId": "list-a", "type": "insertNode",
         "parent": {"nodeId": "row", "slot": "children"},
         "node": {"id": "list-a", "componentId": "layout/lazy-column"}},
        {"operationId": "list-b", "type": "insertNode",
         "parent": {"nodeId": "row", "slot": "children"}, "afterNodeId": "list-a",
         "node": {"id": "list-b", "componentId": "layout/lazy-column"}},
        ${children("list-a", "items", "a", "A", 20).replace("\"A ", "\"A")},
        ${children("list-b", "items", "b", "Bravo", 3)}
        """,
      )

    /** One 24dp cell, drawn three times down the frame by a loop over three rows. */
    fun loopFixture(): String {
      val row =
        """{"type": "object", "fields": {"shade": {"type": "color", "value": "#FF40C463"}}}"""
      return fixture(
        "canvas-device-loop",
        """
        {"operationId": "loop", "type": "insertNode", "parent": null,
         "node": {"id": "loop", "componentId": "layout/for-each",
                  "properties": {"data": {"type": "list", "values": [$row, $row, $row]}}}},
        {"operationId": "cell", "type": "insertNode",
         "parent": {"nodeId": "loop", "slot": "template"},
         "node": {"id": "cell", "componentId": "m3/surface",
                  "properties": {"containerColor": {"type": "binding", "value": "shade"}},
                  "modifiers": [{"type": "size", "widthDp": $CELL_DP, "heightDp": $CELL_DP}]}}
        """,
      )
    }

    fun children(parent: String, slot: String, id: String, label: String, count: Int) =
      (1..count).joinToString(",") { index ->
        val after = if (index == 1) "" else "\"afterNodeId\": \"$id-${index - 1}\","
        """
        {"operationId": "$id$index", "type": "insertNode",
         "parent": {"nodeId": "$parent", "slot": "$slot"}, $after
         "node": {"id": "$id-$index", "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "$label $index"}}}}
        """
      }

    fun fixture(designId: String, operations: String): String =
      """
      {
        "documentSchema": "compose-ui-builder-document/v1-candidate",
        "designId": "$designId",
        "operations": [
          {
            "operationId": "create",
            "type": "createDesign",
            "title": "Device view fixture",
            "catalogPin": {
              "systemId": "m3-catalog",
              "catalogRevision": "candidate",
              "capabilityDigest": "candidate",
              "nativeRuntimeId": "candidate"
            },
            "environment": {
              "widthDp": $FRAME_DP, "heightDp": $FRAME_DP, "density": 1.0, "theme": "dark",
              "dynamicColor": false, "locale": "en-US", "fontScale": 1.0,
              "layoutDirection": "ltr", "windowPosture": "flat",
              "browserZoomPercent": 100, "fixedTime": "2024-05-16T12:00:00Z",
              "animations": "settled", "networkAccess": false
            },
            "stateVariables": {}
          },
          $operations
        ]
      }
      """
  }
}
