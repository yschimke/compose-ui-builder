package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.EDITOR_GESTURES
import ee.schimke.composeai.uibuilder.editor.EDITOR_SHORTCUTS
import ee.schimke.composeai.uibuilder.editor.EditorShortcutsPanel

/**
 * The shortcuts panel on its own, rendering [EDITOR_SHORTCUTS] and [EDITOR_GESTURES] (the key
 * handler's own lists), so changes to what the editor advertises are diffed.
 */
@Preview(widthDp = 560, heightDp = 1080)
@Composable
fun UiBuilderShortcutsPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface { EditorShortcutsPanel(Modifier.padding(20.dp)) }
  }
}
