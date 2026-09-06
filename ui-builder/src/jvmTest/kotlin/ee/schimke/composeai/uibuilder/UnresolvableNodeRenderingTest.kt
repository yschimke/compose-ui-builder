package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap

/**
 * No node may fail the frame.
 *
 * An `asset/image` whose key nothing resolved used to be `error("unsupported asset …")` inside the
 * composition, and a text whose colour token the canvas did not know was the same one property over
 * — so one accepted node made every render of the design fail, for every collaborator, until
 * somebody found it (yschimke/compose-preview-server#484). Both reducers refuse such values at
 * commit now; these tests hold the second line, with validation deliberately bypassed: a document
 * that holds one anyway produces a frame with something visible where the node is.
 */
@OptIn(ExperimentalComposeUiApi::class)
class UnresolvableNodeRenderingTest {
  @Test
  fun `an asset key nothing resolves draws a placeholder rather than throwing`() {
    val pixels =
      render(
        node(
          "photo",
          "asset/image",
          "assetKey" to literal("string", "avatar-lain"),
          "contentDescription" to literal("string", "lain"),
        )
      )

    // The placeholder is a framed box with a cross through it, so it is not one flat colour: the
    // centre, an edge and a corner are not all the same pixel.
    val samples =
      listOf(
        pixels.getColor(SIZE / 2, SIZE / 2),
        pixels.getColor(1, 1),
        pixels.getColor(SIZE / 2, 1),
      )
    assertTrue(samples.distinct().size > 1, "the placeholder is visible: $samples")
  }

  @Test
  fun `a colour token the canvas does not know draws the component's default rather than throwing`() {
    val pixels =
      render(
        node(
          "text",
          "m3/text",
          "text" to literal("string", "Hello"),
          "color" to literal("colorToken", "primaryContainer"),
        )
      )

    // Any frame at all is the property under test; the text is drawn in the theme's default.
    assertTrue(pixels.width == SIZE)
  }

  private fun node(id: String, componentId: String, vararg properties: Pair<String, JsonObject>) =
    UiBuilderNode(
      id = id,
      componentId = componentId,
      properties = JsonObject(properties.toMap()),
      modifiers = JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
      slots = emptyMap(),
    )

  private fun render(root: UiBuilderNode): Bitmap {
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1-candidate",
        id = "unresolvable-${root.id}",
        title = "Unresolvable",
        revision = 0,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf(root.id),
        nodes = mapOf(root.id to root),
      )
    val image = renderComposeScene(SIZE, SIZE, Density(1f)) { UiBuilderSurface(document) }
    return Bitmap().apply { allocN32Pixels(SIZE, SIZE) }.also(image::readPixels)
  }

  private fun literal(type: String, value: String) =
    JsonObject(mapOf("type" to JsonPrimitive(type), "value" to JsonPrimitive(value)))

  private companion object {
    const val SIZE = 64
  }
}
