package ee.schimke.composeai.uibuilder

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.preview.HelloWearWidgetSamplePreview
import ee.schimke.composeai.uibuilder.preview.WearWidgetCodePanePreview
import ee.schimke.composeai.uibuilder.preview.WearWidgetHostShapesPreview
import ee.schimke.composeai.uibuilder.preview.WeatherWearWidgetSamplePreview
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Wear widget sample previews draw their text.
 *
 * The samples write text as `remote-m3/remote-text`, which the canvas draws only through the
 * `wear-m3/text` adapter its catalog names. A preview that renders without that catalog draws every
 * label as "Unsupported component: remote-m3/remote-text", which is what these did when the samples
 * moved off the borrowed `m3/text`.
 */
@OptIn(ExperimentalTestApi::class)
class WearWidgetSamplePreviewTest {

  @Test
  fun `the hello sample draws its text`() =
    runDesktopComposeUiTest(width = 520, height = 240) {
      setContent { HelloWearWidgetSamplePreview() }

      onNodeWithText("Hello, World!").assertExists()
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  @Test
  fun `the weather sample draws its text`() =
    runDesktopComposeUiTest(width = 520, height = 340) {
      setContent { WeatherWearWidgetSamplePreview() }

      onNodeWithText("London").assertExists()
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  @Test
  fun `every host shape draws the weather sample's text`() =
    runDesktopComposeUiTest(width = 1560, height = 440) {
      setContent { WearWidgetHostShapesPreview() }

      assertTrue(onAllNodesWithText("London").fetchSemanticsNodes().size >= 3)
      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }

  @Test
  fun `the code pane preview draws the hello sample's text`() =
    runDesktopComposeUiTest(width = 1600, height = 900) {
      setContent { WearWidgetCodePanePreview() }

      onNodeWithText("Unsupported component", substring = true).assertDoesNotExist()
    }
}
