package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The Wear list screen as the builder's canvas draws it: a long-screenshot stadium at the small
 * round width, with the first screenful outlined over the top cap.
 *
 * Rendered through [UiBuilderSurface] — the renderer the editor canvas and the production export
 * use — so this is the design as an author sees it, not a mock of one. `editorOverlay = false`
 * because the question is what a Wear screen looks like in the builder, not what the builder looks
 * like around it.
 */
@Preview(widthDp = 168, heightDp = 280)
@Composable
fun WearScreenSamplePreview() {
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

/**
 * The same design at all three round sizes, which is the comparison the stadium exists to make
 * cheap.
 *
 * A Wear screen's hard question is where the list wraps, and it wraps differently at 192, 227 and
 * 240dp. On a keyhole canvas that is three scroll positions to check by hand; on the extent it is
 * three columns side by side. The screen width comes from the document's own frame — the Screen
 * inspector's Wear OS presets — rather than from a scaffold property, so switching size is
 * switching device.
 */
@Preview(widthDp = 600, heightDp = 280)
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

/**
 * The Code pane on the Wear screen: the Kotlin the design generates.
 *
 * This is the half the canvas cannot show. `Modifier.transformedHeight(this, spec)` and
 * `SurfaceTransformation(spec)` are on every row of the emitted `TransformingLazyColumn` — they are
 * what a Wear list *is* — while the stadium above draws a plain Column, so reading the two together
 * is how an author knows what the browser is standing in for.
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
 * The catalog the Wear screen previews author against.
 *
 * The packaged M3 capability catalog, which carries every borrowed content component these designs
 * use but neither of the two Wear containers — those are synthesised in `:ui-builder-runtime`,
 * which this module may not depend on. The editor draws a node whose component the catalog does not
 * know; only the inspector is poorer for it, and the panes under test are the canvas and the code.
 */
private val wearScreenPreviewCatalog by lazy {
  ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser.parse(
    checkNotNull(WearScreenCodeExporter::class.java.getResource("/m3-catalog-capabilities-v1.json"))
      .readText()
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
 * Dark at the watch's own 2.0 density, because Wear Material 3 is dark-first and the scaffold
 * paints the screen black whatever the editor theme is. The `widthDp` is the watch: the scaffold
 * reads its diameter from the frame, which is what makes the Screen inspector's Wear OS presets the
 * size control.
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
