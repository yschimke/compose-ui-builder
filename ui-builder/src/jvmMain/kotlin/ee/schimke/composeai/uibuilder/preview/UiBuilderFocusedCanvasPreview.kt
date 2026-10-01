package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.editor.FocusedCanvasUiBuilderChrome
import ee.schimke.composeai.uibuilder.editor.HOVER_EDITOR_WIDTH
import ee.schimke.composeai.uibuilder.editor.NodeCommentCard
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor

/**
 * The focused canvas the MCP App opens a design in (compose-ui-builder#374): the editor with its
 * toolbar, docks, rails and status bar taken away, at the size of a chat host's side panel.
 *
 * The file bar above it belongs to the MCP App host rather than to the editor, so it is not in this
 * picture; the browser evidence in `docs/design/evidence/ui-builder-mcp-app-host/` has both.
 */
@Preview(widthDp = 720, heightDp = 900)
@Composable
fun UiBuilderFocusedCanvasPreview() {
  UiBuilderEditor(
    document = editorChromePreviewDocument,
    catalog = editorChromePreviewCatalog,
    chrome = FocusedCanvasUiBuilderChrome(),
    initialSelectedNodeId = EDITOR_CHROME_PREVIEW_SELECTION,
  )
}

/** The node menu's Comment field, as it opens beside a node: empty, so Send is not offered yet. */
@Preview(widthDp = 300, heightDp = 180)
@Composable
fun UiBuilderNodeCommentCardPreview() {
  MaterialTheme {
    Surface {
      Box(Modifier.padding(12.dp).width(HOVER_EDITOR_WIDTH)) {
        NodeCommentCard(
          label = "Text · search-placeholder",
          onSend = {},
          onDismiss = {},
          onTextInputFocusChanged = {},
        )
      }
    }
  }
}
