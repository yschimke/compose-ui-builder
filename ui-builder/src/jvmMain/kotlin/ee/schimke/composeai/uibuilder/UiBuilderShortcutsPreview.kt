package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The shortcuts panel on its own, so what the editor advertises is diffed.
 *
 * It renders [EDITOR_SHORTCUTS] and [EDITOR_GESTURES] — the same lists the key handler matches
 * against — so adding a chord without a description, or removing one the panel still claims, shows
 * up here as a changed image rather than as a help sheet nobody re-read.
 */
@Preview(widthDp = 560, heightDp = 700)
@Composable
fun UiBuilderShortcutsPreview() {
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface { EditorShortcutsPanel(Modifier.padding(20.dp)) }
  }
}
