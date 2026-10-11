package ee.schimke.composeai.uibuilder

import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.export.helloWidgetUiBuilderDocument
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject

/**
 * A design draws the same whatever content colour its host sets: `UiBuilderSurface` must set the
 * design's own content colour, since its `MaterialTheme` sets none and uncoloured text would
 * otherwise inherit the editor's or a device scene's.
 */
@OptIn(ExperimentalComposeUiApi::class)
class SurfaceContentColorTest {
  @Test
  fun `uncoloured text is drawn in the design's colour, not the host's`() {
    val hello =
      helloWidgetUiBuilderDocument("hello", JsonObject(emptyMap()), JsonObject(emptyMap()))
    val label = hello.nodes.getValue("hello-text")
    val uncoloured =
      hello.copy(
        nodes =
          hello.nodes +
            ("hello-text" to label.copy(properties = JsonObject(label.properties - "color")))
      )

    val onDark = render(uncoloured, host = Color.White)
    val onLight = render(uncoloured, host = Color.Black)

    assertTrue(onDark.contentEquals(onLight), "the label changed colour with its host")
  }

  private fun render(document: UiBuilderDocument, host: Color): IntArray {
    val pixels =
      renderComposeScene(SCENE_PX, SCENE_PX, Density(1f)) {
          CompositionLocalProvider(LocalContentColor provides host) {
            UiBuilderSurface(document = document)
          }
        }
        .toComposeImageBitmap()
        .toPixelMap()
    return IntArray(SCENE_PX * SCENE_PX) { pixels[it % SCENE_PX, it / SCENE_PX].hashCode() }
  }

  private companion object {
    const val SCENE_PX = 240
  }
}
