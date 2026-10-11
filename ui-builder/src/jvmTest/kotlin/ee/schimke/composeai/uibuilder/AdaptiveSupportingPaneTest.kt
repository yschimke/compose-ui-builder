package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.canvas.CanvasExtentLayout
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the adaptive scaffold may collapse, and where not. In every bounded frame
 * `layout/supporting-pane-scaffold` is the real `SupportingPaneScaffold`, so a tablet design loses
 * its supporting pane on a phone. The authoring canvas always draws both panes, so neither is
 * unreachable. Rule: `docs/design/UI_BUILDER_PREVIEW_FIDELITY.md`.
 */
@OptIn(ExperimentalTestApi::class)
class AdaptiveSupportingPaneTest {

  /** A scaffold with one identifiable word in each pane, and nothing else. */
  private fun document(
    layoutMode: String = "expandedTwoPane",
    mainPaneVisible: Boolean = true,
    supportingPaneVisible: Boolean = true,
    mainPaneWidthDp: Int? = null,
    supportingPaneWidthDp: Int? = null,
    paneSpacingDp: Int? = null,
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
                  ) +
                    listOfNotNull(
                        mainPaneWidthDp?.let { "mainPanePreferredWidthDp" to float(it) },
                        supportingPaneWidthDp?.let {
                          "supportingPanePreferredWidthDp" to float(it)
                        },
                        paneSpacingDp?.let { "paneSpacingDp" to float(it) },
                      )
                      .toMap()
                ),
              slots = mapOf("mainPane" to listOf("main"), "supportingPane" to listOf("support")),
            ),
          "main" to text("main", "MainPaneWord"),
          "support" to text("support", "SupportPaneWord"),
        ),
    )

  private fun bool(value: Boolean) =
    JsonObject(mapOf("type" to JsonPrimitive("bool"), "value" to JsonPrimitive(value)))

  private fun float(value: Int) =
    JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(value)))

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
      // `expandedTwoPane` in a compact 411dp window: `calculateThreePaneScaffoldValue` hides the
      // supporting pane. Asserted as *not displayed* since the hidden pane stays composed at zero
      // size.
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
          // `unrolled = true` inside `CanvasExtentLayout`, as `UiBuilderEditor` composes the
          // canvas, to reproduce the unbounded measurement.
          CanvasExtentLayout(Modifier.fillMaxSize()) {
            UiBuilderSurface(document(), unrolled = true)
          }
        }
      }
      // The same document and width as the first test: the editing canvas keeps the supporting pane
      // reachable.
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
      // `layoutMode` isn't consulted on this surface; a hidden pane here would be unreachable.
      onNodeWithText("MainPaneWord").assertIsDisplayed()
      onNodeWithText("SupportPaneWord").assertIsDisplayed()
    }

  @Test
  fun `a supporting pane that is the only pane is the pane that is shown`() =
    runDesktopComposeUiTest(width = 411, height = 914) {
      setContent { MaterialTheme { UiBuilderSurface(document(mainPaneVisible = false)) } }
      // With only a supporting pane, it gets the partition rather than leaving the frame blank.
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

  /**
   * Where the supporting pane starts, at a tablet width, for an authored pair of pane widths. Its
   * text's left edge is where the split fell, since nothing wraps the scaffold's partitions.
   */
  private fun supportingPaneLeft(mainWidthDp: Int, supportingWidthDp: Int): Float {
    var left = 0f
    runDesktopComposeUiTest(width = 1280, height = 800) {
      setContent {
        MaterialTheme {
          UiBuilderSurface(
            document(mainPaneWidthDp = mainWidthDp, supportingPaneWidthDp = supportingWidthDp)
          )
        }
      }
      left = onNodeWithText("SupportPaneWord").fetchSemanticsNode().boundsInRoot.left
    }
    return left
  }

  /**
   * The three declared widths (`mainPanePreferredWidthDp`, `supportingPanePreferredWidthDp`,
   * `paneSpacingDp`) reach the real scaffold (`docs/design/UI_BUILDER_GOOGLE_APP_SAMPLES.md`, gap
   * 2). Asserts direction: a narrow main pane splits early, a wide one late.
   */
  @Test
  fun `an authored pane width moves the split, in the direction it was authored`() {
    val narrowMain = supportingPaneLeft(mainWidthDp = 400, supportingWidthDp = 760)
    val wideMain = supportingPaneLeft(mainWidthDp = 760, supportingWidthDp = 400)

    assertTrue(
      narrowMain < wideMain,
      "the authored main-pane width did not move the split: a 400dp main pane put the supporting " +
        "pane at $narrowMain and a 760dp one put it at $wideMain. Equal values mean the widths " +
        "are being ignored, which is the whole of gap 2.",
    )
    // Not merely ordered but on the right sides of centre, so a change that honoured the widths by
    // some small fraction would not pass. 1280 wide, so the halfway mark is 640.
    assertTrue(
      narrowMain < 640f,
      "a 400dp main pane should split left of centre, not at $narrowMain",
    )
    assertTrue(wideMain > 640f, "a 760dp main pane should split right of centre, not at $wideMain")
  }
}
