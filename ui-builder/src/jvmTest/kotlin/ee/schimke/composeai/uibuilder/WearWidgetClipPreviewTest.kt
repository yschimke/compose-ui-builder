package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import ee.schimke.composeai.uibuilder.canvas.LocalWearWidgetClipContent
import ee.schimke.composeai.uibuilder.canvas.WearWidgetContainerScaffold
import ee.schimke.composeai.uibuilder.export.UiBuilderNode
import ee.schimke.composeai.uibuilder.export.WearWidgetHostSpec
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalComposeUiApi::class)
class WearWidgetClipPreviewTest {
  @Test
  fun `clipped profile cuts overflowing corners while preserving the centre`() {
    fun pixels(clipped: Boolean) =
      renderComposeScene(100, 60, Density(1f)) {
          CompositionLocalProvider(LocalWearWidgetClipContent provides clipped) {
            Box(Modifier.fillMaxSize().background(Color.Green)) {
              WearWidgetContainerScaffold(
                node = UiBuilderNode("root", "remote-m3/widget-container-small"),
                modifier = Modifier,
                spec = WearWidgetHostSpec(100, 60, 0f, 0f, 26f),
                brushes = {},
                hasBrushes = false,
              ) {
                Box(Modifier.fillMaxSize().background(Color.Red))
              }
            }
          }
        }
        .toComposeImageBitmap()
        .toPixelMap()
    val ordinary = pixels(false)
    val clipped = pixels(true)
    assertEquals(Color.Red, ordinary[1, 1])
    assertEquals(Color.Green, clipped[1, 1])
    assertEquals(Color.Red, clipped[50, 30])
  }
}
