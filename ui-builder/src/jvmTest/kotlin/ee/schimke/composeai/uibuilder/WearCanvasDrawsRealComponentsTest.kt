package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.wearcmp.port.WearFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The canvas draws Wear components with Wear Compose, asserted on the composition, not the build: a
 * component can link yet never reach the screen.
 *
 * Assertions only the genuine components produce:
 * - `SwitchButton` publishes `ToggleableState` semantics (a lookalike `Text` in a `Box` publishes
 *   none);
 * - the sub-header labels reach the composition at all.
 *
 * Run on the JVM since the port publishes both variants from one source set and the routing is in
 * `commonMain` (the Wasm test runner needs a Node toolchain the sandbox may lack).
 */
@OptIn(ExperimentalTestApi::class)
class WearCanvasDrawsRealComponentsTest {
  private fun wearDesign(): UiBuilderDocument {
    val file =
      File(
          System.getProperty("uiBuilderDesignFixturesDir")
            ?: "../docs/design/fixtures/ui-builder/designs"
        )
        .resolve("google-home-wear.json")
    assertTrue(file.isFile, "missing the Wear design fixture at $file")
    return UiBuilderReducer.replay(Json.parseToJsonElement(file.readText()).jsonObject).document
  }

  @Test
  fun `a switch row is drawn by a component that publishes toggleable semantics`() =
    runDesktopComposeUiTest(width = 400, height = 1600) {
      setContent { WearCatalogAdapters { UiBuilderSurface(wearDesign(), unrolled = true) } }

      val toggleables =
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
            useUnmergedTree = true,
          )
          .fetchSemanticsNodes()

      assertTrue(
        toggleables.isNotEmpty(),
        "no node published ToggleableState — the switch rows are not being drawn by Wear's " +
          "SwitchButton. A lookalike assembled from a Text and a Box would look similar and " +
          "report nothing, which is the regression this asserts against.",
      )
      assertTrue(
        toggleables.any {
          it.config.getOrNull(SemanticsProperties.ToggleableState) == ToggleableState.On
        },
        "every toggle read Off; the authored `checked` is not reaching the component.",
      )
    }

  @Test
  fun `sub-header labels reach the composition`() =
    runDesktopComposeUiTest(width = 400, height = 1600) {
      setContent { WearCatalogAdapters { UiBuilderSurface(wearDesign(), unrolled = true) } }

      // Authored on a `wear-m3/list-sub-header`, which had no canvas drawing at all before the port
      // — the id resolved to a dashed placeholder, so this text was absent from the tree.
      assertTrue(
        onAllNodesWithText("Living room", substring = true, useUnmergedTree = true)
          .fetchSemanticsNodes()
          .isNotEmpty(),
        "the sub-header's label is not in the composition",
      )
    }

  @Test
  fun `an empty button group draws nothing rather than taking the canvas down`() =
    runDesktopComposeUiTest(width = 400, height = 1600) {
      // The catalog allows a group with no children, and the library's `ButtonGroup` throws
      // measuring one. Both the unrolled extent and a device frame compose it.
      val design = wearDesign()
      val list = design.nodes.getValue("home-list")
      val group = UiBuilderNode(id = "empty-group", componentId = "wear-m3/button-group")
      val withGroup =
        design.copy(
          nodes =
            design.nodes +
              ("empty-group" to group) +
              ("home-list" to
                list.copy(
                  slots =
                    list.slots + ("items" to listOf("empty-group") + list.slots.getValue("items"))
                ))
        )
      var unrolled by mutableStateOf(true)
      setContent { WearCatalogAdapters { UiBuilderSurface(withGroup, unrolled = unrolled) } }
      for (mode in listOf(true, false)) {
        unrolled = mode
        waitForIdle()
        assertTrue(
          onAllNodesWithText("Home", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty(),
          "the screen around an empty group did not draw (unrolled = $unrolled)",
        )
      }
    }

  @Test
  fun `a Wear surface sets Wear text in the face the type scale names`() =
    runDesktopComposeUiTest(width = 400, height = 1600) {
      // The Wear type scale asks for `roboto-flex` by name. Unregistered, the port falls back to
      // the platform's sans, which is wider: the clock lost a digit and watch-width labels wrapped.
      setContent { WearCatalogAdapters { UiBuilderSurface(wearDesign(), unrolled = true) } }
      waitForIdle()
      assertTrue(
        WearFonts.isRegistered(WearFonts.RobotoFlex),
        "Wear's `roboto-flex` was not registered with the port before the screen composed",
      )
    }
}
