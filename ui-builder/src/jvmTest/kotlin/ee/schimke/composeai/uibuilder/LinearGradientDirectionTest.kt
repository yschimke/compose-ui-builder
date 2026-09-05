package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Bitmap

/**
 * `shape/linear-gradient` paints on the axis its `direction` names.
 *
 * The renderer drew every linear gradient top-to-bottom while the Compose exporter read `direction`
 * and emitted all four cases — so a `leftToRight` design looked vertical on the canvas and
 * generated horizontal Kotlin, and only one of the two could be right. It matters here because
 * `WearWidgetBrush` distinguishes the same two axes, so a widget background is where an author
 * would first ask for the horizontal one.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LinearGradientDirectionTest {
  @Test
  fun `topToBottom varies down the axis and not across it`() {
    val pixels = render("topToBottom")

    assertEquals(pixels.getColor(8, 8), pixels.getColor(56, 8), "a row is one colour")
    assertTrue(pixels.getColor(8, 8) != pixels.getColor(8, 56), "the column varies")
  }

  @Test
  fun `leftToRight varies across the axis and not down it`() {
    val pixels = render("leftToRight")

    assertEquals(pixels.getColor(8, 8), pixels.getColor(8, 56), "a column is one colour")
    assertTrue(pixels.getColor(8, 8) != pixels.getColor(56, 8), "the row varies")
  }

  /** Reversing the direction swaps which end each stop is drawn at. */
  @Test
  fun `rightToLeft is leftToRight with the stops exchanged`() {
    val forward = render("leftToRight")
    val reversed = render("rightToLeft")

    assertEquals(forward.getColor(8, 32), reversed.getColor(55, 32))
  }

  private fun render(direction: String): Bitmap {
    val document =
      UiBuilderDocument(
        schema = "compose-ui-builder-document/v1-candidate",
        id = "gradient-$direction",
        title = "Gradient",
        revision = 0,
        catalogPin = JsonObject(emptyMap()),
        environment = JsonObject(emptyMap()),
        stateVariables = JsonObject(emptyMap()),
        roots = listOf("gradient"),
        nodes =
          mapOf(
            "gradient" to
              UiBuilderNode(
                id = "gradient",
                componentId = "shape/linear-gradient",
                properties =
                  JsonObject(
                    mapOf(
                      "startColor" to literal("color", "#FF000000"),
                      "endColor" to literal("color", "#FFFFFFFF"),
                      "direction" to literal("enum", direction),
                    )
                  ),
                modifiers =
                  JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("fillMaxSize"))))),
                slots = emptyMap(),
              )
          ),
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
