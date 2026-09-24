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
 * A Wear text node is drawn with the properties the catalog declares, not with four of them.
 *
 * ## The bug this pins
 *
 * `wear-m3/text` was the last of the three Material 3 renames, and it was the one that read least:
 * `text`, `color`, `style` and `maxLines`. The catalog declares sixteen properties for it —
 * inherited wholesale from `m3/text` — and the mobile branch beside it honours every one, so
 * `fontSizeSp`, `lineHeightSp`, `letterSpacingSp`, `softWrap`, `overflow`, `minLines`,
 * `textDecoration`, `fontWeight` and `fontStyle` were inert on a Wear node and live on a phone.
 *
 * It went unnoticed because the *style* was right: `wearTextStyle` resolves the role names against
 * Wear's own typography, so a `titleMedium` was the correct Wear face and the screen template
 * looked like it matched. What it actually measured was a title 73.0dp wide against the reference's
 * 67.0dp — the template's `fontSizeSp` pins, which exist to correct for the canvas's wider font,
 * were being thrown away. With the pins live the same title measures 64.5dp.
 *
 * ## And the second half of the same bug
 *
 * The branch passed no `onTextLayout` either, so a Wear text node was *declared* a text node —
 * `isUiBuilderTextComponent("wear-m3/text")` is true, and `WearTextComponentTest` exists to keep it
 * true — while never reporting a layout. The inspector waits for the measurements its expected
 * inventory promises, which is exactly the stall that test's KDoc warns a wrong predicate would
 * cause, arriving from the other side. this class's first assertion failing with "the text node
 * reported no layout" rather than a size is how that half was found.
 *
 * ## Why this asserts on bounds and baselines
 *
 * Because that is what the properties change. `fontSizeSp` moves the line height and the baseline;
 * `maxLines` moves the line count. Both are published by the inspection snapshot, so the assertion
 * is about what the renderer did rather than about which branch it took — the same reading
 * `WearCanvasDeviceSizeTest` takes of a ring's stroke.
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
   * A Wear screen holding one label, at a width narrow enough that a long one has to wrap: the same
   * shape the screen template's rows have, without the card around it.
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
