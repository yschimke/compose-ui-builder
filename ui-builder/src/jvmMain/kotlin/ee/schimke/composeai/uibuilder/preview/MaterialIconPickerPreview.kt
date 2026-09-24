package ee.schimke.composeai.uibuilder.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.editor.EditorPropertyControl
import ee.schimke.composeai.uibuilder.editor.EditorPropertyField
import ee.schimke.composeai.uibuilder.editor.GoogleIconPropertyControl

/** The searchable, bounded view over the generated Material icon inventory. */
@Preview(widthDp = 340, heightDp = 560)
@Composable
fun MaterialIconPickerPreview() {
  MaterialTheme {
    Surface {
      GoogleIconPropertyControl(
        field =
          EditorPropertyField(
            nodeId = "preview-icon",
            name = "iconKey",
            label = "Icon",
            required = true,
            written = true,
            control = EditorPropertyControl.Enum,
            value = "filled/chat",
          ),
        onTextInputFocusChanged = {},
        commit = {},
        initiallyExpanded = true,
        initialQuery = "chat",
      )
    }
  }
}
