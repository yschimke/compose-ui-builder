package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.CanvasExtentLayout
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.preview.designFixtureDocument
import kotlin.test.Test

/**
 * The Google tablet samples open in the visual editor.
 *
 * Each is an `m3/navigation-suite-scaffold`, which the editor's canvas draws as the navigation
 * suite's rail in a `Row` beside the content. That canvas measures against an unbounded height, and
 * the rail fills the height it is given: handed `Infinity`, it asked for a 96 x 2147483647 layer
 * and every one of these designs failed to open in the browser.
 */
@OptIn(ExperimentalTestApi::class)
class NavigationSuiteUnrolledTest {

  private fun opensUnrolled(designId: String, label: String) =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      setContent {
        MaterialTheme {
          // The editor's frame, as `UiBuilderEditorCanvas` sizes it: the design's width, and at
          // least — never at most — its height. That open-ended height is what the extent layout
          // then measures its content against, as `Constraints.Infinity`.
          Box(
            Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
              .requiredWidth(1280.dp)
              .requiredHeightIn(min = 800.dp)
          ) {
            CanvasExtentLayout(Modifier.fillMaxSize()) {
              UiBuilderSurface(designFixtureDocument(designId), unrolled = true)
            }
          }
        }
      }
      onAllNodesWithText(label).onFirst().assertIsDisplayed()
    }

  @Test fun `play opens on the canvas`() = opensUnrolled("google-play-tablet", "Games")

  @Test fun `gmail opens on the canvas`() = opensUnrolled("google-gmail-tablet", "Inbox")

  @Test fun `photos opens on the canvas`() = opensUnrolled("google-photos-tablet", "Photos")

  @Test fun `calendar opens on the canvas`() = opensUnrolled("google-calendar-tablet", "Week")

  @Test fun `keep opens on the canvas`() = opensUnrolled("google-keep-tablet", "Notes")
}
