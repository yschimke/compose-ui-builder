package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import ee.schimke.composeai.uibuilder.canvas.DeviceSceneHost
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A device pane follows the design, not only the pointer.
 *
 * The scene composes inside its own frame pump, and that pump used to be restarted by forwarded
 * input alone. An edit invalidated the scene's recomposer and nothing asked it for a frame, so a
 * Wear widget's three host previews kept drawing the empty container they opened on while the
 * canvas beside them filled up — until a pointer happened to cross one of them.
 */
@OptIn(ExperimentalTestApi::class)
class DeviceSceneHostTest {
  @Test
  fun `an edit recomposes the scene with no input to the pane`() = runComposeUiTest {
    var revision by mutableStateOf(0)
    val composed = mutableListOf<Int>()
    setContent {
      val current = revision
      DeviceSceneHost(
        key = "pane",
        sizePx = IntSize(100, 100),
        density = Density(1f),
        rotary = false,
        contentKey = current,
      ) {
        SideEffect { composed += current }
      }
    }
    waitForIdle()
    assertEquals(0, composed.last())

    revision = 1
    waitForIdle()
    assertEquals(1, composed.last())

    revision = 2
    waitForIdle()
    assertEquals(2, composed.last())
  }
}
