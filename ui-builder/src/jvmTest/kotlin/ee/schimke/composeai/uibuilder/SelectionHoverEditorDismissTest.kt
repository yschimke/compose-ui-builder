package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.editor.SelectionHoverEditor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The card that follows the selection over the canvas can be closed. Something is always selected,
 * so without a close control the card could not be got out of the way of the design under it.
 */
@OptIn(ExperimentalTestApi::class)
class SelectionHoverEditorDismissTest {
  @Test
  fun `the selection card has a close control`() = runDesktopComposeUiTest {
    var dismissed = 0
    setContent {
      MaterialTheme {
        SelectionHoverEditor(
          label = "RemoteButton · remote-m3/remote-button",
          fields = emptyList(),
          modifierFields = emptyList(),
          focusTarget = null,
          onFocusHandled = {},
          onCommitProperty = { _, _ -> },
          onCommitModifier = { _, _ -> },
          onTextInputFocusChanged = {},
          onDismiss = { dismissed++ },
        )
      }
    }
    onNodeWithContentDescription("Close selection editor").performClick()
    assertEquals(1, dismissed)
  }
}
