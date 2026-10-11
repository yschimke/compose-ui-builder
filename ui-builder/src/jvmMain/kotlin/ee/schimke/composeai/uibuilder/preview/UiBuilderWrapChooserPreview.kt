package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.EditorCatalogItem
import ee.schimke.composeai.uibuilder.editor.EditorComponentKind
import ee.schimke.composeai.uibuilder.editor.QUICK_WRAP_CONTAINERS
import ee.schimke.composeai.uibuilder.editor.WrapChooserPanel
import ee.schimke.composeai.uibuilder.editor.quickWrapChoices

/** The chooser `Ctrl/Cmd+Alt+G` opens, with all three containers on offer and Row highlighted. */
@Preview(widthDp = 280, heightDp = 260)
@Composable
fun UiBuilderWrapChooserPreview() {
  val choices =
    quickWrapChoices(
      QUICK_WRAP_CONTAINERS.map { id ->
        EditorCatalogItem(
          componentId = id,
          displayName = id.substringAfter('/').replaceFirstChar(Char::uppercase),
          kind = EditorComponentKind.Container,
          group = "Layout",
        )
      }
    )
  MaterialTheme(colorScheme = darkColorScheme()) {
    Surface { WrapChooserPanel(choices, highlighted = 1, onPick = {}, Modifier.padding(20.dp)) }
  }
}
