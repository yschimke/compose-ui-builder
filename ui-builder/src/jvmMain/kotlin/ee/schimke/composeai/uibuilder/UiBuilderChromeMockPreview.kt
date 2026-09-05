package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The proposed editor chrome, drawn as a design in the editor's own catalog.
 *
 * A mock of a redesign is a design, so it is authored the way this project authors designs: as an
 * operations fixture against `m3-catalog`, replayed through [UiBuilderReducer] and rendered by
 * [UiBuilderSurface] — the same renderer the Wasm canvas and the production export use. Nothing
 * here is a picture of a UI; every rail, dock and status line is real `layout/row`, `m3/surface`
 * and `m3/text` the builder can open, select and edit.
 *
 * `docs/design/fixtures/ui-builder/ui-builder-chrome-mock-v1.json` is therefore both the render
 * below and a document that can be created on a live server, which is what makes it reviewable
 * rather than merely describable.
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
