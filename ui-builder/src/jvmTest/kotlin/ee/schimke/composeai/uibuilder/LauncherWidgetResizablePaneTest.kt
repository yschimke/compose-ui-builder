package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapterMappings
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.editor.DesignPreviewPane
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.CanvasAdapterMappingV1
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The resizable launcher pane in a live Preview: the handle drags the frame continuously, the
 * design re-lays out at the nearest cell count as the frame crosses into it, and letting go springs
 * the frame onto that count.
 */
@OptIn(ExperimentalTestApi::class)
class LauncherWidgetResizablePaneTest {

  /**
   * The `remote-widgets` catalog's own `counter-widget` template (remote-m3-catalog
   * `widget-catalog/ui-builder/designs/counter-widget.json`), drawn with the canvas adapters its
   * policy names, so the pane shows the widget the editor shows.
   */
  private val counter = Json {
    ignoreUnknownKeys = true
  }
    .decodeFromString(
      UiBuilderDocument.serializer(),
      """
      {
        "schema": "compose-ui-builder-document/v1-candidate",
        "id": "counter-widget",
        "title": "Counter widget · 3x2 (203×220dp)",
        "revision": 0,
        "template": "counter-widget",
        "catalogPin": {
          "systemId": "remote-widgets",
          "catalogRevision": "candidate",
          "capabilityDigest": "candidate",
          "nativeRuntimeId": "candidate"
        },
        "environment": {
          "widthDp": 203,
          "heightDp": 220,
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
        "roots": [
          "launcher-widget"
        ],
        "nodes": {
          "launcher-widget": {
            "id": "launcher-widget",
            "componentId": "remote-widgets/launcher-widget",
            "properties": {
              "background": {
                "type": "string",
                "value": "#FFF3EDF7"
              }
            },
            "modifiers": [],
            "slots": {
              "content": [
                "counter-row"
              ]
            }
          },
          "counter-row": {
            "id": "counter-row",
            "componentId": "layout/row",
            "properties": {
              "verticalAlignment": {
                "type": "string",
                "value": "centerVertically"
              }
            },
            "modifiers": [
              {
                "type": "fillMaxSize"
              }
            ],
            "slots": {
              "children": [
                "minus",
                "count",
                "plus"
              ]
            }
          },
          "minus": {
            "id": "minus",
            "componentId": "remote-widgets/widget-button",
            "properties": {
              "text": {
                "type": "string",
                "value": "-"
              }
            },
            "modifiers": [
              {
                "type": "weight",
                "weight": 1
              }
            ],
            "slots": {}
          },
          "count": {
            "id": "count",
            "componentId": "remote-widgets/remote-text",
            "properties": {
              "text": {
                "type": "string",
                "value": "0"
              },
              "fontSize": {
                "type": "float",
                "value": 48
              },
              "color": {
                "type": "string",
                "value": "#FF1D1B20"
              }
            },
            "modifiers": [],
            "slots": {}
          },
          "plus": {
            "id": "plus",
            "componentId": "remote-widgets/widget-button",
            "properties": {
              "text": {
                "type": "string",
                "value": "+"
              }
            },
            "modifiers": [
              {
                "type": "weight",
                "weight": 1
              }
            ],
            "slots": {}
          }
        }
      }
      """
        .trimIndent(),
    )

  private val catalogCanvas =
    mapOf(
      "remote-widgets/launcher-widget" to "layout/box",
      "remote-widgets/widget-surface" to "layout/box",
      "remote-widgets/widget-button" to "m3/button",
      "remote-widgets/widget-title" to "m3/text",
      "remote-widgets/widget-label" to "m3/text",
    )

  /**
   * The policy's `canvasMapping`s: Remote Compose's `fontSize` is what `m3/text` calls
   * `fontSizeSp`.
   */
  private val catalogMappings =
    listOf(
        "remote-widgets/widget-title",
        "remote-widgets/widget-label",
      )
      .associateWith {
        Json.decodeFromString(
          CanvasAdapterMappingV1.serializer(),
          """{"properties": {"fontSizeSp": "fontSize"}}""",
        )
      }

  @Test
  fun `the handle resizes live and lands on whole cells`() =
    runDesktopComposeUiTest(width = 1400, height = 1000) {
      mainClock.autoAdvance = false
      setContent {
        MaterialTheme {
          CompositionLocalProvider(
            LocalUiBuilderCanvasAdapters provides catalogCanvas,
            LocalUiBuilderCatalogComponentIds provides catalogCanvas.keys,
            LocalUiBuilderCanvasAdapterMappings provides catalogMappings,
          ) {
            Box(Modifier.size(1400.dp, 1000.dp).testTag("preview")) {
              DesignPreviewPane(
                counter,
                variants = emptyList(),
                modifier = Modifier.size(1400.dp, 1000.dp),
              )
            }
          }
        }
      }
      var shot = 0
      fun capture(name: String) {
        val output =
          File(System.getProperty("uiBuilderProjectDir"), "build/launcher-resize-evidence").apply {
            mkdirs()
          }
        File(output, "${shot++}-$name.png")
          .writeBytes(
            checkNotNull(
                Image.makeFromBitmap(onNodeWithTag("preview").captureToImage().asSkiaBitmap())
                  .encodeToData(EncodedImageFormat.PNG)
              )
              .bytes
          )
      }
      fun label(size: String) = onNodeWithText("Resizable · $size", substring = true).assertExists()
      val handle = onNodeWithContentDescription("Resize widget")
      fun SemanticsNodeInteraction.centre(): Pair<Dp, Dp> =
        getBoundsInRoot().let { (it.left + it.right) / 2 to (it.top + it.bottom) / 2 }

      mainClock.advanceTimeBy(500)
      // The design's own size first: the counter is a 3x2.
      label("3x2")
      capture("3x2")
      val (startX, startY) = handle.centre()

      // Held a third of a cell left: the frame has moved with the pointer, the design has not.
      handle.performTouchInput {
        down(center)
        moveBy(Offset(-10f, 0f))
        moveBy(Offset(-60f, 0f))
      }
      mainClock.advanceTimeBy(100)
      label("3x2")
      val (heldX, _) = handle.centre()
      assertTrue(heldX < startX, "the frame follows the handle while held: $startX → $heldX")
      capture("held-3x2")

      // Back right past half a cell and up a row: the design re-lays out at 4x1 while still held.
      handle.performTouchInput { moveBy(Offset(330f, -320f)) }
      mainClock.advanceTimeBy(100)
      label("4x1")
      capture("held-4x1")

      // Let go: the frame springs onto the whole cell count, a little short of where it was held.
      handle.performTouchInput { up() }
      mainClock.advanceTimeBy(80)
      capture("settling-4x1")
      mainClock.advanceTimeBy(1500)
      label("4x1")
      val (endX, endY) = handle.centre()
      assertTrue(endX > startX && endY < startY, "($startX, $startY) → ($endX, $endY)")
      capture("4x1")
    }
}
