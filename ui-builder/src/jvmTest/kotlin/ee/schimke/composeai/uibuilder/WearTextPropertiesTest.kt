package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderInspectionSnapshot
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderTextInspection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * A Wear text node is drawn with all the properties the catalog declares (inherited from `m3/text`:
 * `fontSizeSp`, `lineHeightSp`, `letterSpacingSp`, `softWrap`, `overflow`, `minLines`,
 * `textDecoration`, `fontWeight`, `fontStyle`, …), not just `text`, `color`, `style` and
 * `maxLines`. The template's `fontSizeSp` pins depend on it.
 *
 * It must also report its text layout (`onTextLayout`), since
 * `isUiBuilderTextComponent("wear-m3/text")` is true and the inspector waits for those measurements
 * (see `WearTextComponentTest`).
 *
 * Asserted on inspected bounds and baselines, which is what the properties change.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WearTextPropertiesTest {

  @Test
  fun `a Wear text node is drawn at the size the design sets`() {
    val small = text(fontSizeSp = 12f)
    val large = text(fontSizeSp = 24f)

    // The screen's frame pane gives content the viewport's height, so its node bounds stay 192dp
    // however its text measures. A baseline is the text metric, rather than the slot it occupies.
    assertTrue(
      large.text.firstBaselineY > small.text.firstBaselineY,
      "the first baseline should move with the size: ${small.text.firstBaselineY} then " +
        "${large.text.firstBaselineY}",
    )
  }

  @Test
  fun `a Wear text node wraps where the design says it does`() {
    val oneLine = text(fontSizeSp = 12f, maxLines = 1)
    val threeLines = text(fontSizeSp = 12f, maxLines = 3)

    assertEquals(1, oneLine.text.lineCount, "`maxLines = 1` should clip to one line")
    assertTrue(
      threeLines.text.lineCount > 1,
      "a long label with `maxLines = 3` should wrap, but drew " +
        "${threeLines.text.lineCount} line(s)",
    )
  }

  private fun text(fontSizeSp: Float, maxLines: Int = Int.MAX_VALUE): TextNode {
    var snapshot: UiBuilderInspectionSnapshot? = null
    renderComposeScene(SCENE_PX, SCENE_PX, Density(1f)) {
      WearCatalogAdapters {
        UiBuilderSurface(
          document = document(fontSizeSp, maxLines),
          onInspectionSnapshot = { snapshot = it },
        )
      }
    }
    val node =
      checkNotNull(snapshot).nodes.firstOrNull { it.nodeId == "label" }
        ?: error("the text node was not measured")
    return TextNode(
      bounds = checkNotNull(node.bounds) { "the text node has no bounds" },
      text = checkNotNull(node.text) { "the text node reported no layout" },
    )
  }

  private data class TextNode(val bounds: UiBuilderPixelBounds, val text: UiBuilderTextInspection)

  /**
   * A Wear screen holding one label, narrow enough that a long one wraps: the template rows' shape
   * without the card.
   */
  private fun document(fontSizeSp: Float, maxLines: Int) =
    UiBuilderDocument(
      schema = "compose-ui-builder-document/v1-candidate",
      id = "wear-text-properties",
      title = "Wear text properties",
      revision = 0,
      catalogPin = JsonObject(emptyMap()),
      environment =
        Json.parseToJsonElement(
            """
            {
              "widthDp": 192, "heightDp": 192, "density": 2.0, "theme": "dark",
              "locale": "en-US", "fontScale": 1.0, "layoutDirection": "ltr",
              "animations": "settled"
            }
            """
          )
          .jsonObject,
      stateVariables = JsonObject(emptyMap()),
      roots = listOf("screen"),
      nodes =
        mapOf(
          "screen" to
            UiBuilderNode(
              id = "screen",
              componentId = "wear-m3/screen-scaffold",
              modifiers = JsonArray(emptyList()),
              slots = mapOf("content" to listOf("label")),
            ),
          "label" to
            UiBuilderNode(
              id = "label",
              componentId = "wear-m3/text",
              properties =
                JsonObject(
                  mapOf(
                    "text" to literal(LABEL),
                    "style" to literal("bodySmall"),
                    "fontSizeSp" to literal(fontSizeSp.toString()),
                    "maxLines" to literal(maxLines.toString()),
                  )
                ),
              modifiers = JsonArray(listOf(fillMaxWidth())),
              slots = emptyMap(),
            ),
        ),
    )

  private fun literal(value: String) =
    JsonObject(mapOf("type" to JsonPrimitive("string"), "value" to JsonPrimitive(value)))

  private fun fillMaxWidth() = JsonObject(mapOf("type" to JsonPrimitive("fillMaxWidth")))

  private companion object {
    /** Room for the 192dp frame at the document's 2.0 density. */
    const val SCENE_PX = 384

    /** Long enough to need three lines at 12sp across a 172dp content column. */
    const val LABEL =
      "A label long enough that it cannot fit on one line of a watch, which is the case the " +
        "template's rows are full of"
  }
}
