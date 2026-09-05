package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
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
