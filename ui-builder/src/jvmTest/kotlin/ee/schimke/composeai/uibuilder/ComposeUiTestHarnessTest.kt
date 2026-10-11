package ee.schimke.composeai.uibuilder

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test

/**
 * The harness itself: a scene composes and a node can be found. If this fails, nothing else in the
 * UI suite means anything.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeUiTestHarnessTest {
  @Test
  fun `the compose test scene composes and can be queried`() = runComposeUiTest {
    setContent { Text("harness is up") }
    onNodeWithText("harness is up").assertIsDisplayed()
  }
}
