package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the adaptive scaffold is allowed to collapse, and where it is not.
 *
 * `layout/supporting-pane-scaffold` is `androidx.compose.material3.adaptive`'s own
 * `SupportingPaneScaffold` in every **bounded** frame, so a design authored for a tablet loses its
 * supporting pane on a phone exactly as it would in the app. The authoring canvas is the deliberate
 * exception and draws both panes at any width — a collapsed pane there is a subtree that cannot be
 * selected, dropped into or edited, and the width that would collapse it is the canvas's own rather
 * than any device's. `docs/design/UI_BUILDER_PREVIEW_FIDELITY.md` is the rule; this is the pin.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveSupportingPaneTest {

  /** A scaffold with one identifiable word in each pane, and nothing else. */
  private fun document(
    layoutMode: String = "expandedTwoPane",
    mainPaneVisible: Boolean = true,
    supportingPaneVisible: Boolean = true,
  ) =
    UiBuilderDocument(
      schema = "ui-builder/v1",
      id = "panes",
      title = "Panes",
      revision = 1,
      catalogPin = JsonObject(mapOf("catalogId" to JsonPrimitive("m3-catalog"))),
      environment =
        JsonObject(
          mapOf(
            "widthDp" to JsonPrimitive(411),
            "heightDp" to JsonPrimitive(914),
            "density" to JsonPrimitive(1.0),
          )
        ),
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("scaffold"),
      nodes =
        mapOf(
          "scaffold" to
            UiBuilderNode(
              id = "scaffold",
              componentId = "layout/supporting-pane-scaffold",
              properties =
                JsonObject(
                  mapOf(
                    "layoutMode" to
                      JsonObject(
                        mapOf(
                          "type" to JsonPrimitive("enum"),
                          "value" to JsonPrimitive(layoutMode),
                        )
                      ),
                    "mainPaneVisible" to bool(mainPaneVisible),
                    "supportingPaneVisible" to bool(supportingPaneVisible),
                  )
                ),
              slots = mapOf("mainPane" to listOf("main"), "supportingPane" to listOf("support")),
            ),
          "main" to text("main", "MainPaneWord"),
          "support" to text("support", "SupportPaneWord"),
        ),
    )

  private fun bool(value: Boolean) =
    JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(value)))

  private fun text(id: String, value: String) =
    UiBuilderNode(
      id = id,
      componentId = "m3/text",
      properties =
        JsonObject(
          mapOf(
            "text" to
              JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))
          )
        ),
    )

  @Test
  fun `a phone-width bounded frame drops the supporting pane, because the library does`() =
    runDesktopComposeUiTest(width = 411, height = 914) {
      setContent { MaterialTheme { UiBuilderSurface(document()) } }
      onNodeWithText("MainPaneWord").assertIsDisplayed()
      // Not this repository's threshold: `expandedTwoPane` asks for two panes and a 411 dp window
      // is compact, so `calculateThreePaneScaffoldValue` hides the supporting one.
      //
      // *Not displayed* rather than *absent*: the scaffold keeps a hidden pane in the composition
      // and measures it to nothing, so its content is still reachable by `onNodeWithText` at zero
      // size. Asserting existence here would pass against a scaffold that had collapsed nothing.
      onNodeWithText("SupportPaneWord").assertIsNotDisplayed()
    }

  @Test
  fun `a tablet-width bounded frame keeps both panes`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      setContent { MaterialTheme { UiBuilderSurface(document()) } }
      onNodeWithText("MainPaneWord").assertIsDisplayed()
      onNodeWithText("SupportPaneWord").assertIsDisplayed()
    }

  @Test
  fun `singlePane is one pane on a tablet too, because that is what it asks for`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      setContent { MaterialTheme { UiBuilderSurface(document(layoutMode = "singlePane")) } }
      onNodeWithText("MainPaneWord").assertIsDisplayed()
      onNodeWithText("SupportPaneWord").assertIsNotDisplayed()
    }

  @Test
  fun `the visual editor keeps both panes at a phone width`() =
    runDesktopComposeUiTest(width = 411, height = 914) {
      setContent {
        MaterialTheme {
          // `unrolled = true` inside `CanvasExtentLayout` is the editor's canvas, exactly as
          // `UiBuilderEditor` composes it — the flag alone would not reproduce the unbounded
          // measurement the real component cannot survive.
          CanvasExtentLayout(Modifier.fillMaxSize()) {
            UiBuilderSurface(document(), unrolled = true)
          }
        }
      }
      // The same document and the same width as the first test, which loses the supporting pane.
      // This is the rung-1 licence, not a disagreement to reconcile: an author editing the
      // supporting pane's subtree must be able to see and reach it.
      onNodeWithText("MainPaneWord").assertIsDisplayed()
      onNodeWithText("SupportPaneWord").assertIsDisplayed()
    }

  @Test
  fun `the visual editor keeps both panes at a tablet width too, and singlePane is no exception`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      setContent {
        MaterialTheme {
          CanvasExtentLayout(Modifier.fillMaxSize()) {
            UiBuilderSurface(document(layoutMode = "singlePane"), unrolled = true)
          }
        }
      }
      // `layoutMode` is not consulted on this surface at all. It is the constrained frame's
      // directive now, and a pane hidden here would be a subtree with no way to reach it — which
      // is a worse outcome than showing an author a pane their phone will not.
      onNodeWithText("MainPaneWord").assertIsDisplayed()
      onNodeWithText("SupportPaneWord").assertIsDisplayed()
    }

  @Test
  fun `a supporting pane that is the only pane is the pane that is shown`() =
    runDesktopComposeUiTest(width = 411, height = 914) {
      setContent { MaterialTheme { UiBuilderSurface(document(mainPaneVisible = false)) } }
      // One partition goes to the primary pane, so masking the primary afterwards would leave a
      // value with everything hidden and a blank frame. A design whose only pane is the supporting
      // one is valid, and it is the pane the frame owes.
      onNodeWithText("SupportPaneWord").assertIsDisplayed()
      onNodeWithText("MainPaneWord").assertIsNotDisplayed()
    }

  @Test
  fun `a supporting-only design on a singlePane frame is not blank either`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      setContent {
        MaterialTheme {
          UiBuilderSurface(document(layoutMode = "singlePane", mainPaneVisible = false))
        }
      }
      onNodeWithText("SupportPaneWord").assertIsDisplayed()
      onNodeWithText("MainPaneWord").assertIsNotDisplayed()
    }
}
