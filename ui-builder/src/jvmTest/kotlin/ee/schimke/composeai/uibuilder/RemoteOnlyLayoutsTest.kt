package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
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
import ee.schimke.composeai.uibuilder.canvas.CollapsibleLinearLayout
import ee.schimke.composeai.uibuilder.canvas.FitBoxLayout
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * The canvas's stand-ins for `RemoteFitBox` and `RemoteCollapsibleColumn`/`Row` follow the player's
 * rules (remote-core's `FitBoxLayout` and `CollapsibleColumnLayout`): a fit box shows the first
 * child that fits unsqueezed; a collapsible hides the lowest priority first, a tie the later child,
 * and never a child with no priority while one with a priority remains.
 */
@OptIn(ExperimentalTestApi::class)
class RemoteOnlyLayoutsTest {

  @Test
  fun `a collapsible column hides the lowest priority first`() = runDesktopComposeUiTest {
    setContent {
      Box(Modifier.size(100.dp, 50.dp)) {
        CollapsibleLinearLayout(
          vertical = true,
          // 20 + 20 + 20 does not fit 50: exactly one goes, and it is the priority-1 child even
          // though a priority-2 child comes after it.
          priorities = listOf(null, 1f, 2f),
          weights = listOf(null, null, null),
          horizontalArrangement = Arrangement.Start,
          verticalArrangement = Arrangement.Top,
          horizontalAlignment = Alignment.Start,
          verticalAlignment = Alignment.Top,
        ) {
          listOf("keep", "low", "high").forEach { Box(Modifier.size(20.dp).testTag(it)) }
        }
      }
    }

    onNodeWithTag("keep").assertExists()
    onNodeWithTag("high").assertExists()
    onNodeWithTag("low").assertNotPlaced()
  }

  @Test
  fun `a collapsible row breaks a priority tie by hiding the later child`() =
    runDesktopComposeUiTest {
      setContent {
        Box(Modifier.size(50.dp, 20.dp)) {
          CollapsibleLinearLayout(
            vertical = false,
            priorities = listOf(1f, 1f, null),
            weights = listOf(null, null, null),
            horizontalArrangement = Arrangement.Start,
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.Top,
          ) {
            listOf("first", "second", "unranked").forEach { Box(Modifier.size(20.dp).testTag(it)) }
          }
        }
      }

      onNodeWithTag("first").assertExists()
      onNodeWithTag("unranked").assertExists()
      onNodeWithTag("second").assertNotPlaced()
    }

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
