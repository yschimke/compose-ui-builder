package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.editor.atLauncherSize
import ee.schimke.composeai.uibuilder.export.LauncherWidgetGrid
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

/**
 * `remote-widgets/adaptive-layout` on the canvas: each launcher pane draws the slot the widget
 * would show at that pane's size. The design is authored at 4x1, so the resizable pane starts on
 * Medium; the fixed panes — 2x1, 2x2, 3x2 and 4x2 — show Compact three times and Expanded once.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherAdaptiveLayoutCanvasTest {

  private val adaptive = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "adaptive-widget",
        "title": "Adaptive widget · 4x1 (276×102dp)",
        "revision": 0,
        "catalogPin": {"systemId": "remote-widgets", "catalogRevision": "candidate", "capabilityDigest": "candidate", "nativeRuntimeId": "candidate"},
        "environment": {
                "widthDp": 276,
                "heightDp": 102,
                "density": 2.75,
                "theme": "light",
                "dynamicColor": false,
                "locale": "en-US",
                "fontScale": 1,
                "layoutDirection": "ltr",
                "windowPosture": "flat",
                "animations": "settled",
                "networkAccess": false
              },
              "stateVariables": {},
        "roots": ["launcher-widget"],
        "nodes": {
          "launcher-widget": {"id": "launcher-widget", "componentId": "remote-widgets/launcher-widget", "properties": {}, "modifiers": [], "slots": {"content": ["adaptive"]}},
          "adaptive": {"id": "adaptive", "componentId": "remote-widgets/adaptive-layout", "properties": {}, "modifiers": [], "slots": {"compact": ["compact-title"], "medium": ["medium-title"], "expanded": ["expanded-title"]}},
          "compact-title": {"id": "compact-title", "componentId": "remote-widgets/widget-title", "properties": {"text": {"type": "string", "value": "Compact"}}, "modifiers": [], "slots": {}},
          "medium-title": {"id": "medium-title", "componentId": "remote-widgets/widget-title", "properties": {"text": {"type": "string", "value": "Medium"}}, "modifiers": [], "slots": {}},
          "expanded-title": {"id": "expanded-title", "componentId": "remote-widgets/widget-title", "properties": {"text": {"type": "string", "value": "Expanded"}}, "modifiers": [], "slots": {}}
        }
      }
      """
        .trimIndent(),
    )

  private val catalogCanvas =
    mapOf(
      "remote-widgets/launcher-widget" to "layout/box",
      "remote-widgets/adaptive-layout" to "layout/box",
      "remote-widgets/widget-title" to "m3/text",
    )

  @Test
  fun `the canvas draws the slot each grid size picks`() {
    val expected =
      listOf(
        "2x1" to "Compact",
        "2x2" to "Compact",
        "3x1" to "Compact",
        "3x2" to "Compact",
        "4x1" to "Medium",
        "4x2" to "Expanded",
        "5x2" to "Expanded",
      )
    expected.forEach { (size, slot) ->
      runDesktopComposeUiTest(width = 600, height = 600) {
        val sized = adaptive.atLauncherSize(LauncherWidgetGrid.parse(size)!!)
        setContent {
          MaterialTheme {
            CompositionLocalProvider(
              LocalUiBuilderCanvasAdapters provides catalogCanvas,
              LocalUiBuilderCatalogComponentIds provides catalogCanvas.keys,
            ) {
              UiBuilderSurface(sized, editorOverlay = false)
            }
          }
        }
        listOf("Compact", "Medium", "Expanded").forEach { title ->
          val count = onAllNodesWithText(title).fetchSemanticsNodes().size
          assertEquals(if (title == slot) 1 else 0, count, "$title at $size")
        }
      }
    }
  }
}
