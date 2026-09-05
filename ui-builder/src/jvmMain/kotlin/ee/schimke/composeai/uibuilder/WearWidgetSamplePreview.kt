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
 * The Hello widget from `android/wear-os-samples`' `WearWidget` sample, as a UI-builder design.
 *
 * Rendered through [UiBuilderSurface] — the same renderer the editor canvas and the production
 * export use — so this is the design as an author would see it on the canvas, not a mock of one.
 * `editorOverlay = false` because the question is what the widget looks like, not what the editor
 * looks like around it; [UiBuilderEditorChromePreview] covers the second.
 *
 * Sized to the Small host canvas plus a margin, so the squircle frame is visible against the page
 * rather than bled to the edge: the frame is the *host's* contribution and the point of these two
 * previews is that the design sits inside it.
 */
@Preview(widthDp = 260, heightDp = 120)
@Composable
fun HelloWearWidgetSamplePreview() {
  UiBuilderSurface(
    document =
      helloWidgetUiBuilderDocument(
        designId = "hello-widget-preview",
        catalogPin = wearWidgetSampleCatalogPin,
        environment = wearWidgetSampleEnvironment,
      ),
    editorOverlay = false,
  )
}

/** The Weather widget from the same sample, in the sunny state its own previews render. */
@Preview(widthDp = 260, heightDp = 170)
@Composable
fun WeatherWearWidgetSamplePreview() {
  UiBuilderSurface(
    document =
      weatherWidgetUiBuilderDocument(
        designId = "weather-widget-preview",
        catalogPin = wearWidgetSampleCatalogPin,
        environment = wearWidgetSampleEnvironment,
      ),
    editorOverlay = false,
  )
}

/**
 * The empty Small and Large host frames, side by side, with no widget background declared.
 *
 * This is what the two blank templates open on, and the state the scaffold's default decides:
 * `WearWidgetContainer` paints `#FF272430` — a literal it forks from Wear Material 3's
 * `surfaceContainerLow` — for a widget that declares no background of its own, so both frames are
 * that colour whatever theme the editor is in.
 */
@Preview(widthDp = 500, heightDp = 170)
@Composable
fun EmptyWearWidgetContainerPreview() {
  Row(
    Modifier.fillMaxSize(),
    horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Boxed rather than modified: `UiBuilderSurface` fills whatever it is given and takes no
    // modifier, so the row's weights have to be carried by a wrapper.
    WearWidgetScaffoldSize.entries.forEach { size ->
      Box(Modifier.weight(1f).fillMaxHeight()) {
        UiBuilderSurface(
          document =
            wearWidgetUiBuilderDocument(
              designId = "empty-${size.name.lowercase()}-preview",
              catalogPin = wearWidgetSampleCatalogPin,
              environment = wearWidgetSampleEnvironment,
              size = size,
            ),
          editorOverlay = false,
        )
      }
    }
  }
}

/**
 * The `remote-m3` pin these designs are authored against.
 *
 * Spelled out rather than read from the frozen Jetcaster fixture: that fixture pins `m3-catalog`,
 * and a preview claiming a design belongs to a catalog it does not is a lie the renderer happens
 * not to check.
 */
private val wearWidgetSampleCatalogPin: JsonObject =
  Json.parseToJsonElement(
      """
      {
        "systemId": "remote-m3",
        "catalogRevision": "wear-widget-scaffolds-v1",
        "capabilityDigest": "candidate",
        "nativeRuntimeId": "candidate"
      }
      """
    )
    .jsonObject

/**
 * Light, at 1.0 density and a settled clock, because a widget preview that moved would make the
 * render lane's diff meaningless. The watch surface itself is drawn by the scaffold, so nothing
 * here is claiming a device.
 */
private val wearWidgetSampleEnvironment: JsonObject =
  Json.parseToJsonElement(
      """
      {
        "widthDp": 260,
        "heightDp": 170,
        "density": 1.0,
        "theme": "light",
        "dynamicColor": false,
        "locale": "en-US",
        "fontScale": 1.0,
        "layoutDirection": "ltr",
        "windowPosture": "flat",
        "browserZoomPercent": 100,
        "fixedTime": "2024-05-16T12:00:00Z",
        "animations": "settled",
        "networkAccess": false
      }
      """
    )
    .jsonObject
