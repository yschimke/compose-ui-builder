package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogComponentIds
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import kotlin.test.Test

/**
 * A catalog names the adapter its own runtime registers (`m3-catalog` publishes `m3/text` with
 * `wasm.canvas = "material3/Text"`), and the in-process canvas never declares those names. Before
 * the fix the in-process canvas, which still draws `m3/text`, dispatched only on that name and drew
 * the dashed placeholder instead, so every palette thumbnail of the served m3 catalog was a
 * wireframe.
 */
@OptIn(ExperimentalTestApi::class)
class CatalogRuntimeAdapterFallbackTest {
  @Test
  fun `a runtime-only adapter id falls back to the component's own case`() =
    runDesktopComposeUiTest(width = 300, height = 120) {
      setContent {
        CompositionLocalProvider(
          LocalUiBuilderCatalogComponentIds provides setOf("m3/text"),
          LocalUiBuilderCanvasAdapters provides mapOf("m3/text" to "material3/Text"),
        ) {
          UiBuilderSurface(document = document, editorOverlay = false)
        }
      }
      onNodeWithText("Hello palette").assertExists()
    }

  /**
   * `placeholder` is the one non-builder adapter name a catalog writes to say it does not draw the
   * component here (the Wear catalog does, for `wear-m3/transforming-lazy-column`). It must keep
   * the stand-in, even for a component this canvas has a case for.
   */
  @Test
  fun `the placeholder adapter keeps the stand-in`() =
    runDesktopComposeUiTest(width = 300, height = 120) {
      setContent {
        CompositionLocalProvider(
          LocalUiBuilderCatalogComponentIds provides setOf("m3/text"),
          LocalUiBuilderCanvasAdapters provides mapOf("m3/text" to "placeholder"),
        ) {
          UiBuilderSurface(document = document, editorOverlay = false)
        }
      }
      onNodeWithText("Hello palette").assertDoesNotExist()
      onNodeWithText("text").assertExists()
    }

  private val document =
    decodeProductionRendererDocument(
      """
      {"schema":"compose-ui-builder-document/v1-candidate","id":"runtime-adapter",
       "title":"Runtime adapter","revision":1,
       "catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate",
         "capabilityDigest":"candidate","nativeRuntimeId":"candidate"},
       "environment":{"widthDp":300,"heightDp":120,"density":1.0,"theme":"light",
         "locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},
       "stateVariables":{},"roots":["t"],
       "nodes":{"t":{"id":"t","componentId":"m3/text",
         "properties":{"text":{"type":"string","value":"Hello palette"}},
         "modifiers":[],"slots":{},"eventBindings":{}}}}
      """
        .trimIndent()
    )
}
