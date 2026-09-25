package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderCanvasRenderer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * A catalog's pinned renderer draws the editing canvas and nothing else.
 *
 * In the browser that renderer is a sandboxed iframe booting its own Wasm runtime. It used to be
 * reachable from every surface that draws a design, so the component list started one per tile —
 * dozens of runtimes for one list, each polling and each a DOM layer re-placed by hand on scroll —
 * and a drag started another for its ghost. This counts live renderer instances with the list open
 * and a component in the air: one, the canvas.
 */
@OptIn(ExperimentalTestApi::class)
class PinnedRendererScopeTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document = UiBuilderReducer.replay(FIXTURE.jsonObject).document

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()

  @Test
  fun `only the editing canvas draws with the pinned renderer`() =
    runDesktopComposeUiTest(width = 1400, height = 900) {
      var live = 0
      var peak = 0
      var calls = 0
      val renderer: UiBuilderCanvasRenderer = { _, _, _, _, _, _ ->
        calls++
        DisposableEffect(Unit) {
          live++
          peak = maxOf(peak, live)
          onDispose { live-- }
        }
        Box(Modifier.fillMaxSize())
      }
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            chrome = PointerTestUiBuilderChrome,
            initialComponentsOpen = true,
            initialCanvasZoom = 1f,
            canvasRenderer = renderer,
          )
        }
      }
      waitForIdle()
      assertTrue(calls > 0, "the editing canvas draws with the pinned renderer")
      assertEquals(1, peak, "the component list's tiles drew with it too")

      // A component in the air: the ghost is drawn in-process, not by booting another runtime.
      onNodeWithContentDescription("Component catalog search").performTextInput("Text")
      waitForIdle()
      onNodeWithContentDescription("Drag Text").performMouseInput {
        moveTo(center)
        press(MouseButton.Primary)
        moveTo(center + Offset(60f, 0f))
        moveTo(center + Offset(400f, 0f))
      }
      waitForIdle()
      assertEquals(1, peak, "the drag ghost drew with it")
      onNodeWithContentDescription("Drag Text").performMouseInput { release(MouseButton.Primary) }
    }

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
              },
              {
                "operationId": "b",
                "type": "insertNode",
                "parent": {"nodeId": "beside-column", "slot": "children"},
                "afterNodeId": "beside-a",
                "node": {
                  "id": "beside-b",
                  "componentId": "m3/text",
                  "properties": {"text": {"type": "string", "value": "B"}}
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
