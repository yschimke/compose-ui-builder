package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The proposed editor chrome, drawn as a design in the editor's own catalog: an operations fixture
 * against `m3-catalog` replayed through [UiBuilderReducer] and rendered by [UiBuilderSurface], so
 * every rail, dock and status line is a real, editable node.
 * `docs/design/fixtures/ui-builder/ui-builder-chrome-mock-v1.json` can also be created on a live
 * server.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun UiBuilderChromeMockPreview() {
  UiBuilderSurface(document = chromeMockDocument, editorOverlay = false)
}

private val chromeMockDocument: UiBuilderDocument by lazy {
  UiBuilderReducer.replay(
      Json.parseToJsonElement(previewResource("/ui-builder-chrome-mock-v1.json")).jsonObject
    )
    .document
}
