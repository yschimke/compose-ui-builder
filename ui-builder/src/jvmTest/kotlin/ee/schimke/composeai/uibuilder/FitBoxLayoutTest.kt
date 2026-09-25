package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.FitBoxLayout
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The canvas's stand-in for `RemoteFitBox` follows the player's rule (`FitBoxLayout` in
 * remote-core): it shows the first child that fits unsqueezed, and nothing after it.
 */
@OptIn(ExperimentalTestApi::class)
class FitBoxLayoutTest {

  @Test
  fun `a fit box shows the first child that fits`() = runDesktopComposeUiTest {
    setContent {
      Box(Modifier.size(60.dp, 30.dp)) {
        FitBoxLayout(alignment = Alignment.Center) {
          Box(Modifier.size(120.dp, 20.dp).testTag("wide"))
          Box(Modifier.size(50.dp, 20.dp).testTag("medium"))
          Box(Modifier.size(10.dp, 10.dp).testTag("small"))
        }
      }
    }

    onNodeWithTag("medium").assertExists()
    onNodeWithTag("wide").assertNotPlaced()
    onNodeWithTag("small").assertNotPlaced()
  }

  /** Unplaced, or never composed into the tree at all: either way nothing is drawn. */
  private fun SemanticsNodeInteraction.assertNotPlaced() {
    val placed = runCatching { fetchSemanticsNode().layoutInfo.isPlaced }.getOrDefault(false)
    assertFalse(placed, "expected the node not to be placed")
  }
}
