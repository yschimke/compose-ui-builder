package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A drag that reaches the edge of the workspace scrolls the design under the pointer.
 *
 * A long design's lower slots are off-screen, and the drag has to be able to reach them without the
 * author letting go, scrolling by hand, and starting again. The scroll is reported upward — the
 * offset is what a hit-test reconciliation will need once the inspection's boxes and the drawn
 * pointer are taught to agree under scroll, which is a separate, pre-existing gap.
 */
@OptIn(ExperimentalTestApi::class)
class CanvasAutoScrollTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `a drag held at the bottom edge scrolls the design upward`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
        )
      }
      waitForIdle()

      // Held at the bottom edge of a 700px viewport, inside the frame's width.
      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 690f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      assertTrue(scroll.y > 100f, "the content should have scrolled under the pointer ($scroll)")
    }

  @Test
  fun `a drag in the middle of the workspace scrolls nothing`() =
    runDesktopComposeUiTest(width = 900, height = 700) {
      var scroll = Offset.Zero
      var dragPosition by mutableStateOf<Offset?>(null)
      setContent {
        CanvasHost(
          document,
          zoom = 1f,
          dragPosition = dragPosition,
          onScroll = { scroll = it },
        )
      }
      waitForIdle()

      // Comfortably inside the band-free middle of the workspace.
      mainClock.autoAdvance = false
      dragPosition = Offset(160f, 350f)
      repeat(60) { mainClock.advanceTimeBy(200) }
      dragPosition = null
      mainClock.advanceTimeBy(200)
      waitForIdle()

      assertTrue(scroll == Offset.Zero, "the middle of the workspace does not scroll ($scroll)")
    }

  @Composable
  private fun CanvasHost(
    document: UiBuilderDocument,
    zoom: Float,
    dragPosition: Offset?,
    onScroll: (Offset) -> Unit,
  ) {
    PinnedDesignCanvas(
      document = document,
      selectedNodeId = null,
      onNodeSelected = {},
      onCanvasMetrics = { _, _, _ -> },
      onCanvasBounds = {},
      dropHovered = false,
      showSelectionOverlay = true,
      dragPosition = dragPosition,
      onCanvasScroll = onScroll,
      reference = ReferenceOverlayState(mintedIds = 0),
      onMarkDrawn = { _, _ -> },
      onPieceMoved = { _, _, _ -> },
      collaborators = emptyList(),
      commentThreads = emptyList(),
      selectedThreadId = null,
      onCommentThreadSelected = {},
      onInspectionSnapshot = null,
      onInspectionInvalidated = null,
      selectionMenu = {},
      hoverEditor = null,
      zoom = zoom,
      onZoomChanged = {},
    )
  }

  private companion object {
    /**
     * A design whose content is far taller than the 700px viewport: a column of 150 texts, so the
     * extent scrolls and the bottom edge has somewhere to take the drag.
     */
    private val FIXTURE: kotlinx.serialization.json.JsonObject
      get() {
        val rows =
          (1..150).joinToString(",") { index ->
            val after = if (index == 1) "" else "\"afterNodeId\": \"tall-${index - 1}\","
            """
          {
            "operationId": "t$index",
            "type": "insertNode",
            "parent": {"nodeId": "tall-column", "slot": "children"},
            $after
            "node": {
              "id": "tall-$index",
              "componentId": "m3/text",
              "properties": {"text": {"type": "string", "value": "Row $index"}}
            }
          }
          """
          }
        return Json.parseToJsonElement(
            """
          {
            "documentSchema": "compose-ui-builder-document/v1-candidate",
            "designId": "canvas-auto-scroll-fixture",
            "operations": [
              {
                "operationId": "create",
                "type": "createDesign",
                "title": "Auto scroll fixture",
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
                "node": {"id": "tall-scaffold", "componentId": "layout/scaffold"}
              },
              {
                "operationId": "column",
                "type": "insertNode",
                "parent": {"nodeId": "tall-scaffold", "slot": "content"},
                "node": {"id": "tall-column", "componentId": "layout/column"}
              },
              ${rows}
            ]
          }
          """
              .trimIndent()
          )
          .jsonObject
      }
  }
}
