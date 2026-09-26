package ee.schimke.composeai.uibuilder.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearScreenCodeExporter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The Wear components on the canvas, one row each: every one drawn by the Wear Compose port.
 *
 * ## What this preview is for
 *
 * It used to be the picture of the opposite decision. When the canvas had no Wear library to link,
 * each of these was a dashed placeholder carrying its name, and this preview said that a row which
 * started drawing a real component would be a regression. The port changed that: the canvas draws
 * the real `CheckboxButton`, `Slider`, `DatePicker` and the rest, and a row that went back to a
 * placeholder, or to a lookalike, is now the regression.
 *
 * It had also stopped drawing anything. It composed the surface without the pinned catalog's canvas
 * adapters, so the screen root had no drawing and the capture was the red *Unsupported component:
 * wear-m3/screen-scaffold* box — the bug the design-fixture previews were fixed for, which this one
 * never got. It goes through [PinnedCatalog] now.
 *
 * Unrolled and wrapped, like the extent fixtures: the palette is taller than a watch, and the point
 * is every row.
 */
@Preview
@Composable
fun WearComponentPalettePreview() {
  PinnedCatalog(wearComponentPaletteDocument) {
    Box(Modifier.wrapContentSize(Alignment.TopCenter, unbounded = true)) {
      UiBuilderSurface(
        document = wearComponentPaletteDocument,
        editorOverlay = false,
        unrolled = true,
      )
    }
  }
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
        // A group of two buttons, as the palette inserts one: an empty group draws nothing.
        val groupChildren =
          if (componentId == WearScreenCodeExporter.BUTTON_GROUP) {
            listOf("Play", "Queue").mapIndexed { button, label ->
              val buttonId = "row-$index-button-$button"
              val labelId = "$buttonId-label"
              put(
                buttonId,
                UiBuilderNode(
                  id = buttonId,
                  componentId = WearScreenCodeExporter.BUTTON,
                  slots = mapOf("content" to listOf(labelId)),
                ),
              )
              put(
                labelId,
                UiBuilderNode(
                  id = labelId,
                  componentId = WearScreenCodeExporter.TEXT,
                  properties = JsonObject(mapOf("text" to wearLiteral(label))),
                ),
              )
              buttonId
            }
          } else {
            emptyList()
          }
        put(
          "row-$index",
          UiBuilderNode(
            id = "row-$index",
            componentId = componentId,
            properties = JsonObject(properties.mapValues { (_, value) -> wearLiteral(value) }),
            modifiers = JsonArray(emptyList()),
            slots = if (groupChildren.isEmpty()) emptyMap() else mapOf("children" to groupChildren),
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
