package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * Every background `WearWidgetBrush` can carry, in the Small container: the host default, a solid
 * colour, a vertical gradient and an image.
 *
 * The four factories are the whole API — `color`, `verticalGradient`, `horizontalGradient` and
 * `image` — and the last three take shapes a string property cannot hold, so they are authored as
 * nodes in the container's `background` slot. That is not a workaround: `WearWidgetBrush` is a
 * *chain* of drawing elements the container folds over, drawing each into the same round rect, and
 * an ordered slot is that chain.
 *
 * The image tile is honest about one thing: this renderer resolves an `asset/image` to real pixels
 * only for the two project-owned artwork keys, and to a placeholder otherwise. What it proves is
 * that the brush slot composes and clips an image to the frame — not that arbitrary widget artwork
 * resolves in the browser, which is the builder's asset-registry question rather than this
 * scaffold's.
 */
@Preview(widthDp = 560, heightDp = 260)
@Composable
fun WearWidgetBackgroundBrushPreview() {
  val tiles =
    listOf(
      widgetBackgroundDocument("brush-default", "Default"),
      widgetBackgroundDocument("brush-colour", "Colour", background = "#FF2196F3"),
      // The pair wear-m3-catalog's own GradientBackground sticker declares.
      widgetBackgroundDocument(
        "brush-gradient",
        "Gradient",
        brushes =
          listOf(
            previewNode(
              "brush-gradient-layer",
              "shape/linear-gradient",
              "startColor" to previewLiteral("color", "#FF101820"),
              "endColor" to previewLiteral("color", "#FF2C4A6E"),
              "direction" to previewLiteral("enum", "topToBottom"),
            )
          ),
      ),
      widgetBackgroundDocument(
        "brush-image",
        "Image",
        brushes =
          listOf(
            previewNode(
              "brush-image-layer",
              "asset/image",
              "assetKey" to previewLiteral("assetKey", ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY),
              "contentScale" to previewLiteral("enum", "crop"),
            )
          ),
      ),
    )
  Column(
    Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
  ) {
    tiles.chunked(2).forEach { row ->
      // Weighted, because `UiBuilderSurface` fills whatever it is given: an unweighted first Row
      // takes the Column's whole height and the second never gets drawn.
      Row(
        Modifier.weight(1f).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        row.forEach { document ->
          Box(Modifier.weight(1f)) { UiBuilderSurface(document = document, editorOverlay = false) }
        }
      }
    }
  }
}

/** A Small container carrying [brushes] in its background slot and [label] as its content. */
private fun widgetBackgroundDocument(
  designId: String,
  label: String,
  background: String? = null,
  brushes: List<UiBuilderNode> = emptyList(),
): UiBuilderDocument {
  val container =
    UiBuilderNode(
      id = "container",
      componentId = WearWidgetScaffoldSize.Small.componentId,
      properties =
        JsonObject(
          background?.let { mapOf("background" to previewLiteral("color", it)) } ?: emptyMap()
        ),
      modifiers = JsonArray(emptyList()),
      slots =
        mapOf("background" to brushes.map(UiBuilderNode::id), "content" to listOf("caption-box")),
    )
  // Boxed for the same reason the Hello template boxes its text: `layout/box` aligns each child by
  // that child's own `alignment`, and the container's content slot does not align at all.
  val captionBox =
    UiBuilderNode(
      id = "caption-box",
      componentId = "layout/box",
      properties = JsonObject(emptyMap()),
      modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
      slots = mapOf("children" to listOf("caption")),
    )
  val caption =
    previewNode(
      "caption",
      "m3/text",
      "text" to previewLiteral("string", label),
      "color" to previewLiteral("color", "#FFFFFFFF"),
      "fontSizeSp" to
        JsonObject(mapOf("type" to JsonPrimitive("float"), "value" to JsonPrimitive(16))),
      "alignment" to previewLiteral("enum", "center"),
    )
  return UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = designId,
    title = "$label background",
    revision = 0,
    catalogPin = wearWidgetSampleCatalogPin,
    environment = wearWidgetSampleEnvironment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("container"),
    nodes = (listOf(container, captionBox, caption) + brushes).associateBy(UiBuilderNode::id),
  )
}

private fun previewNode(
  id: String,
  componentId: String,
  vararg properties: Pair<String, JsonObject>,
) =
  UiBuilderNode(
    id = id,
    componentId = componentId,
    properties = JsonObject(properties.toMap()),
    modifiers = JsonArray(emptyList()),
    slots = emptyMap(),
  )

private fun previewLiteral(type: String, value: String) =
  JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

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
