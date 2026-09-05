package ee.schimke.composeai.uibuilder

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * What the canvas shows for the Wear components it deliberately does not draw.
 *
 * ## What this preview is for
 *
 * `wear-m3` now publishes seventeen components with no Material 3 counterpart — the selection
 * controls, the sliders, the pickers, the dialogs — and the canvas draws **none** of them, on
 * purpose. `androidx.wear.compose:compose-material3` is an Android AAR, a Wasm build can never link
 * one, and
 * [`docs/design/UI_BUILDER_WEAR_SCREEN.md`](../../../../../../../docs/design/UI_BUILDER_WEAR_SCREEN.md)
 * rules out assembling a lookalike from Material 3 pieces: an impression of upstream with nothing
 * in this build to check it against is wrong silently, which is the worst way to be wrong in a
 * surface an author trusts.
 *
 * So each one is a dashed outline carrying its name, its label where it has one, and its children.
 * This preview is the picture of that decision, and the reason it exists rather than being left
 * uncaptured: the placeholder is a real visual surface, and the next change to it should be diffed
 * without anyone remembering to look. The *components* are looked at somewhere else entirely — the
 * native render lane compiles this design's own generated Kotlin against real Wear Compose on the
 * Android/Robolectric daemon, which is what `wear-m3` declares authoritative.
 *
 * A row here that starts drawing a Wear component rather than naming one is a regression, not an
 * improvement.
 */
@Preview(widthDp = 200, heightDp = 620)
@Composable
fun WearNativeOnlyPlaceholderPreview() {
  UiBuilderSurface(document = wearComponentPaletteDocument, editorOverlay = false)
}

/**
 * One list, one row per component, in the order the palette groups them.
 *
 * Built as a literal document rather than through the reducer because the point is the *drawing*: a
 * reducer round trip would be testing insertion, and insertion is `StarterContentTest`'s job.
 */
private val wearComponentPaletteDocument: UiBuilderDocument by lazy {
  val rows =
    listOf(
      WearScreenCodeExporter.LIST_SUB_HEADER to mapOf("text" to "Controls"),
      WearScreenCodeExporter.CHECKBOX_BUTTON to mapOf("label" to "Notifications"),
      WearScreenCodeExporter.SWITCH_BUTTON to mapOf("label" to "Wi-Fi"),
      WearScreenCodeExporter.RADIO_BUTTON to mapOf("label" to "Daily"),
      WearScreenCodeExporter.SLIDER to emptyMap(),
      WearScreenCodeExporter.PROGRESS_INDICATOR to emptyMap(),
      WearScreenCodeExporter.BUTTON_GROUP to emptyMap(),
      WearScreenCodeExporter.DATE_PICKER to emptyMap(),
      WearScreenCodeExporter.TIME_PICKER to emptyMap(),
    )
  val nodes =
    buildMap<String, UiBuilderNode> {
      put(
        "wear-screen",
        UiBuilderNode(
          id = "wear-screen",
          componentId = WearScreenCodeExporter.SCAFFOLD,
          properties = JsonObject(mapOf("timeText" to wearLiteral("10:10"))),
          slots = mapOf("content" to listOf("wear-list")),
        ),
      )
      put(
        "wear-list",
        UiBuilderNode(
          id = "wear-list",
          componentId = WearScreenCodeExporter.TRANSFORMING_LAZY_COLUMN,
          slots = mapOf("items" to rows.indices.map { "row-$it" }),
        ),
      )
      rows.forEachIndexed { index, (componentId, properties) ->
        put(
          "row-$index",
          UiBuilderNode(
            id = "row-$index",
            componentId = componentId,
            properties = JsonObject(properties.mapValues { (_, value) -> wearLiteral(value) }),
            modifiers = JsonArray(emptyList()),
          ),
        )
      }
    }
  UiBuilderDocument(
    schema = "compose-ui-builder-document/v1-candidate",
    id = "wear-component-palette",
    title = "Wear components",
    revision = 0,
    catalogPin = wearComponentPaletteCatalogPin,
    environment = wearComponentPaletteEnvironment,
    stateVariables = JsonObject(emptyMap()),
    roots = listOf("wear-screen"),
    nodes = nodes,
  )
}

private fun wearLiteral(value: String): JsonObject =
  JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

private val wearComponentPaletteCatalogPin: JsonObject =
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

/** 192dp, dark, at the watch's own density — the frame a `wear-m3` design is created on. */
private val wearComponentPaletteEnvironment: JsonObject =
  Json.parseToJsonElement(
      """
      {
        "widthDp": 192,
        "heightDp": 384,
        "density": 2.0,
        "theme": "dark",
        "locale": "en-US",
        "fontScale": 1.0,
        "layoutDirection": "ltr",
        "windowPosture": "flat",
        "animations": "settled",
        "networkAccess": false
      }
      """
    )
    .jsonObject
