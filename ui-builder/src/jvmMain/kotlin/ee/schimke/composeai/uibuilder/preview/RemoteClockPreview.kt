package ee.schimke.composeai.uibuilder.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.ProvideProductionCatalog
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.RemoteClockTemplate
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The Clock template: Remote content whose hour marks come from a loop and whose hands turn with
 * the time, drawn by the editor canvas.
 *
 * At 10:10:30 rather than the shared environment's noon, where all three hands would stack on
 * twelve and the picture could not show that each turns by its own formula.
 */
@Preview(widthDp = 220, heightDp = 220)
@Composable
fun RemoteClockPreview() {
  val document =
    RemoteClockTemplate.document(
      designId = "remote-clock-preview",
      catalogPin = wearWidgetSampleCatalogPin,
      environment =
        JsonObject(
          wearWidgetSampleEnvironment + ("fixedTime" to JsonPrimitive("2024-05-16T10:10:30Z"))
        ),
    )
  ProvideProductionCatalog(document) {
    UiBuilderSurface(document = document, editorOverlay = false)
  }
}
