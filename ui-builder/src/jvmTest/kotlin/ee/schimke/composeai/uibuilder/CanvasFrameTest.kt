package ee.schimke.composeai.uibuilder

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The canvas frames a Wear widget by its host container, not by the environment.
 *
 * Widget designs carry the phone fixture's 1280x800dp environment, and framing by it drew the
 * widget as a small tile in the middle of an empty tablet that "Fit" then fitted.
 */
class CanvasFrameTest {
  private val tablet =
    JsonObject(mapOf("widthDp" to JsonPrimitive(1280), "heightDp" to JsonPrimitive(800)))

  @Test
  fun `a widget is framed by its host container in the shape being viewed`() {
    val weather = weatherWidgetUiBuilderDocument("weather", JsonObject(emptyMap()), tablet)

    // The Large host's frames, from the same table the preview panes draw at.
    assertEquals(232f to 144f, weather.canvasFrameDp(WearWidgetHostShape.Rectangular))
    assertEquals(216f to 124f, weather.canvasFrameDp(WearWidgetHostShape.Squircle))
    assertEquals(230f to 168f, weather.canvasFrameDp(WearWidgetHostShape.Round))
  }

  @Test
  fun `a screen is framed by its environment`() {
    val screen = blankUiBuilderDocument("screen", JsonObject(emptyMap()), tablet)

    assertEquals(1280f to 800f, screen.canvasFrameDp(WearWidgetHostShape.Default))
  }
}
