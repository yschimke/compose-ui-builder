package ee.schimke.composeai.uibuilder.preview

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
import ee.schimke.composeai.uibuilder.ProvideProductionCatalog
import ee.schimke.composeai.uibuilder.artwork.ANDROID_DEVELOPERS_BACKSTAGE_ARTWORK_KEY
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetHostShape
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetHostShape
import ee.schimke.composeai.uibuilder.export.WearWidgetScaffoldSize
import ee.schimke.composeai.uibuilder.export.helloWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.wearWidgetUiBuilderDocument
import ee.schimke.composeai.uibuilder.export.weatherWidgetUiBuilderDocument
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Hello widget from `android/wear-os-samples`' `WearWidget` sample, as a UI-builder design,
 * rendered through [UiBuilderSurface] (the canvas and export renderer) with `editorOverlay =
 * false`. Sized to the Small host canvas plus a margin so the host's squircle frame is visible.
 */
@Preview(widthDp = 260, heightDp = 120)
@Composable
fun HelloWearWidgetSamplePreview() {
  val document =
    helloWidgetUiBuilderDocument(
      designId = "hello-widget-preview",
      catalogPin = wearWidgetSampleCatalogPin,
      environment = wearWidgetSampleEnvironment,
    )
  ProvideProductionCatalog(document) {
    UiBuilderSurface(document = document, editorOverlay = false)
  }
}

/** The Weather widget from the same sample, in the sunny state its own previews render. */
@Preview(widthDp = 260, heightDp = 170)
@Composable
fun WeatherWearWidgetSamplePreview() {
  val document =
    weatherWidgetUiBuilderDocument(
      designId = "weather-widget-preview",
      catalogPin = wearWidgetSampleCatalogPin,
      environment = wearWidgetSampleEnvironment,
    )
  ProvideProductionCatalog(document) {
    UiBuilderSurface(document = document, editorOverlay = false)
  }
}

/**
 * The same Weather widget in every host container shape, side by side: the canvas half of the
 * host-shape view ([LocalWearWidgetHostShape]).
 *
 * The frames really differ: at Large the squircle gives 200×108dp inside a uniform 8dp, the
 * rectangle 168×112dp inside 32/16dp, the round one 160×136dp inside 35/16dp. Sized to hold all
 * three Large frames (216, 232 and 230dp) plus gaps.
 */
@Preview(widthDp = 780, heightDp = 220)
@Composable
fun WearWidgetHostShapesPreview() {
  val document =
    weatherWidgetUiBuilderDocument(
      designId = "weather-widget-shapes",
      catalogPin = wearWidgetSampleCatalogPin,
      environment = wearWidgetSampleEnvironment,
    )
  ProvideProductionCatalog(document) {
    Row(
      Modifier.fillMaxSize(),
      horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      WearWidgetHostShape.entries.forEach { shape ->
        Box(Modifier.weight(1f).fillMaxHeight()) {
          UiBuilderSurface(
            document = document,
            editorOverlay = false,
            wearWidgetHostShape = shape,
          )
        }
      }
    }
  }
}

/**
 * The empty Small and Large host frames, with no widget background declared: both show
 * `WearWidgetContainer`'s default `#FF272430` whatever the editor theme.
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
 * The Code pane on the Hello widget: `WearWidgetCodeExporter`'s output (a `GlanceWearWidget`, its
 * content composable, and a `@Preview` via the real host tooling), since `remote-m3` has no
 * component record. The host container doesn't appear; the launcher draws it.
 */
@Preview(widthDp = 1600, heightDp = 900)
@Composable
fun WearWidgetCodePanePreview() {
  UiBuilderEditor(
    document =
      helloWidgetUiBuilderDocument(
        designId = "hello-widget-code",
        catalogPin = wearWidgetSampleCatalogPin,
        environment = wearWidgetSampleEnvironment,
      ),
    catalog = wearWidgetPreviewCatalog,
    initialSelectedNodeId = "hello-text",
    initialCodePaneVisible = true,
  )
}

/**
 * The catalog the widget previews author against: the packaged `remote-m3` one, which carries the
 * widget containers and `remote-m3/remote-text` (drawn by the `wear-m3/text` adapter it names).
 */
private val wearWidgetPreviewCatalog by lazy {
  CapabilityCatalogParser.parse(
    checkNotNull(WearWidgetScaffoldSize::class.java.getResource("/remote-m3-capabilities-v1.json"))
      .readText()
  )
}

/**
 * Every background `WearWidgetBrush` can carry, in the Small container: the host default, a solid
 * colour, a vertical gradient and an image.
 *
 * `WearWidgetBrush` is a chain of drawing elements over the same round rect, so non-colour brushes
 * are authored as ordered nodes in the container's `background` slot. The image tile only resolves
 * for the two project-owned artwork keys; it proves the slot composes and clips an image, not
 * general asset resolution.
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
 * The `remote-m3` pin these designs are authored against, spelled out because the Jetcaster fixture
 * pins `m3-catalog`.
 */
internal val wearWidgetSampleCatalogPin: JsonObject =
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
 * Light, 1.0 density and a settled clock, so the render lane's diff is stable. The scaffold draws
 * the watch surface, so no device is claimed.
 */
internal val wearWidgetSampleEnvironment: JsonObject =
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
