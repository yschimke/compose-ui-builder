package ee.schimke.composeai.uibuilder.preview

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
import ee.schimke.composeai.uibuilder.editor.EditorExportFormat
import ee.schimke.composeai.uibuilder.editor.ExportMenuRows
import ee.schimke.composeai.uibuilder.editor.UiBuilderExportHost
import ee.schimke.composeai.uibuilder.editor.exportFormatsFor
import ee.schimke.composeai.uibuilder.editor.exportMenuEntries

/**
 * The Export menu's rows, drawn flat so they can be diffed (the popup isn't captured by a static
 * render). Both formats, each group led by the Figma route as [exportMenuEntries] orders it.
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
 * A host offering every format and doing nothing, enough for the toolbar's Export button; previews
 * never touch clipboard or network.
 */
internal val PREVIEW_EXPORT_HOST: UiBuilderExportHost =
  object : UiBuilderExportHost {
    override val formats: List<EditorExportFormat> = exportFormatsFor(svg = true, png = true)

    override suspend fun copyPicture(format: EditorExportFormat): String = "a preview never copies"

    override suspend fun copyLink(format: EditorExportFormat): String = "a preview never copies"

    override suspend fun download(format: EditorExportFormat): String = "a preview never downloads"
  }
