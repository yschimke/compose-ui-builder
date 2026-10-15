package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCanvasAdapters
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderCatalogPlatform
import ee.schimke.composeai.uibuilder.canvas.LocalUiBuilderFrameGeometry
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.canvasAdapterIds
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import ee.schimke.composeai.uibuilder.frameGeometry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The Wear list screen as the builder's canvas draws it: a long-screenshot stadium at the small
 * round width, with the first screenful outlined over the top cap. Rendered through
 * [UiBuilderSurface] with `editorOverlay = false`.
 */
@Preview(widthDp = 170, heightDp = 410)
@Composable
fun WearScreenSamplePreview() {
  WearScreenPreviewFrame {
    UiBuilderSurface(
      document =
        wearScreenUiBuilderDocument(
          designId = "wear-screen-preview",
          catalogPin = wearScreenSampleCatalogPin,
          environment = wearScreenSampleEnvironment(SMALL_ROUND_DP),
        ),
      editorOverlay = false,
    )
  }
}

/**
 * The same design at all three round sizes (192, 227 and 240dp), side by side, since the list wraps
 * differently at each. The width comes from the document's frame (the Screen inspector's Wear OS
 * presets).
 */
@Preview(widthDp = 580, heightDp = 410)
@Composable
fun WearScreenBreakpointsPreview() {
  Row(
    Modifier.fillMaxSize(),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.Top,
  ) {
    // Boxed rather than modified: `UiBuilderSurface` fills whatever it is given and takes no
    // modifier, so the row's weights have to be carried by a wrapper.
    listOf(SMALL_ROUND_DP, LARGE_ROUND_DP, XL_ROUND_DP).forEach { widthDp ->
      Box(Modifier.weight(1f).fillMaxHeight()) {
        WearScreenPreviewFrame {
          UiBuilderSurface(
            document =
              wearScreenUiBuilderDocument(
                designId = "wear-screen-$widthDp",
                catalogPin = wearScreenSampleCatalogPin,
                environment = wearScreenSampleEnvironment(widthDp),
              ),
            editorOverlay = false,
          )
        }
      }
    }
  }
}

/**
 * The Code pane on the Wear screen: the Kotlin is what ships. The canvas draws the same transformed
 * rows in a device frame; only the unrolled extent lays them out as a plain column.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun WearScreenCodePanePreview() {
  UiBuilderEditor(
    document =
      wearScreenUiBuilderDocument(
        designId = "wear-screen-code",
        catalogPin = wearScreenSampleCatalogPin,
        environment = wearScreenSampleEnvironment(SMALL_ROUND_DP),
      ),
    catalog = wearScreenPreviewCatalog,
    initialSelectedNodeId = "wear-list",
    initialCodePaneVisible = true,
  )
}

/**
 * The catalog the Wear screen previews author against: the Wear golden kept beside the renderer's
 * fixtures, which carries every component used and the frame geometry the renderer reads.
 */
private val wearScreenPreviewCatalog by lazy { publishedPreviewCatalog("wear-m3") }

/**
 * The frame and adapter the catalog declares, provided as the editor does for every surface (see
 * `UiBuilderEditor`), since this preview hosts `UiBuilderSurface` itself.
 */
@Composable
private fun WearScreenPreviewFrame(content: @Composable () -> Unit) {
  CompositionLocalProvider(
    LocalUiBuilderCanvasAdapters provides wearScreenPreviewCatalog.canvasAdapterIds,
    LocalUiBuilderFrameGeometry provides wearScreenPreviewCatalog.frameGeometry,
    LocalUiBuilderCatalogPlatform provides wearScreenPreviewCatalog.platform.wireValue,
    content = content,
  )
}

private val wearScreenSampleCatalogPin: JsonObject =
  Json.parseToJsonElement(
      """
      {
        "systemId": "wear-m3",
        "catalogRevision": "wear-screen-scaffold-v1",
        "capabilityDigest": "candidate",
        "nativeRuntimeId": "candidate"
      }
      """
    )
    .jsonObject

/**
 * Dark at the watch's 2.0 density (Wear Material 3 is dark-first). The `widthDp` is the watch
 * diameter the scaffold reads.
 */
private fun wearScreenSampleEnvironment(widthDp: Int): JsonObject =
  Json.parseToJsonElement(
      """
      {
        "widthDp": $widthDp,
        "heightDp": 384,
        "density": 2.0,
        "theme": "dark",
        "fontScale": 1.0,
        "locale": "en-US",
        "layoutDirection": "ltr"
      }
      """
    )
    .jsonObject

private const val SMALL_ROUND_DP = 192

private const val LARGE_ROUND_DP = 227

private const val XL_ROUND_DP = 240
