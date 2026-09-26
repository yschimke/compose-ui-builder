package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.UiBuilderRendererSurfaceModeV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A catalog runtime is sent a document and a surface size, and nothing else about the pane it
 * fills. So the widget's host shape travels in the document it is sent.
 *
 * The editing canvas is the only surface the runtime draws. The preview panes — one per host shape
 * — used to be runtime surfaces too, which in the browser is a sandboxed iframe booting its own
 * Wasm per pane; they are drawn in-process now, in their shape through `LocalWearWidgetHostShape`,
 * and this pins that none of them reaches the runtime.
 */
@OptIn(ExperimentalTestApi::class)
class RuntimeWidgetHostShapeTest {
  @Test
  fun `each runtime surface is told the host shape of the pane it fills`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      val catalog =
        CapabilityCatalogParser.parse(
          checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
        )
      val widget =
        wearWidgetUiBuilderDocument(
          "hello",
          JsonObject(emptyMap()),
          JsonObject(emptyMap()),
          WearWidgetScaffoldSize.Small,
        )
      val sent = mutableListOf<Pair<UiBuilderRendererSurfaceModeV2, UiBuilderDocument>>()
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            widget,
            catalog,
            canvasRenderer = { document, surface, _, _, _, _ ->
              sent += surface.mode to document
              Box(Modifier.fillMaxSize())
            },
          )
        }
      }
      waitForIdle()

      fun shapesFor(mode: UiBuilderRendererSurfaceModeV2) =
        sent
          .filter { it.first == mode }
          .map { (_, document) ->
            (document.environment[WearWidgetHostShape.ENVIRONMENT_KEY] as? JsonPrimitive)
              ?.contentOrNull
          }
          .toSet()
      // The editing canvas is drawn in the editor's chosen shape, the rectangular default here.
      assertEquals(
        setOf(WearWidgetHostShape.Default.id),
        shapesFor(UiBuilderRendererSurfaceModeV2.AUTHORING_UNROLLED),
      )
      // Each device preview pane is drawn by the runtime in its own host's shape: the in-process
      // renderer cannot draw a remote-m3 widget, so a pane without the runtime showed stand-ins.
      assertEquals(
        WearWidgetHostShape.entries.map { it.id }.toSet(),
        shapesFor(UiBuilderRendererSurfaceModeV2.DEVICE),
      )
      // The shape is on the copy a surface is sent, never on the design itself.
      assertTrue(WearWidgetHostShape.ENVIRONMENT_KEY !in widget.environment)
    }
}
