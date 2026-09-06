package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * The Export menu's rows, drawn flat so they can be diffed.
 *
 * The menu itself is a popup, which a static render does not capture; the chrome previews show the
 * toolbar button and this shows what pressing it lists. Both formats, so every row the menu can
 * carry is on the page: the Figma route first in each group, as [exportMenuEntries] orders it.
 */
@Preview(widthDp = 420, heightDp = 560)
@Composable
fun UiBuilderExportMenuPreview() {
  Surface(color = MaterialTheme.colorScheme.surface) {
    Column(Modifier.width(400.dp).padding(8.dp)) {
      Text(
        "Export",
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelLarge,
      )
      ExportMenuRows(exportMenuEntries(PREVIEW_EXPORT_HOST.formats)) {}
    }
  }
}

/**
 * A host that offers every format and does nothing: enough for the toolbar to show its Export
 * button, which is what the chrome previews diff. A preview may not touch a clipboard or a network,
 * and none of these rows is ever pressed in one.
 */
internal val PREVIEW_EXPORT_HOST: UiBuilderExportHost =
  object : UiBuilderExportHost {
    override val formats: List<EditorExportFormat> = exportFormatsFor(svg = true, png = true)

    override suspend fun copyPicture(format: EditorExportFormat): String = "a preview never copies"

    override suspend fun copyLink(format: EditorExportFormat): String = "a preview never copies"

    override suspend fun download(format: EditorExportFormat): String = "a preview never downloads"
  }
