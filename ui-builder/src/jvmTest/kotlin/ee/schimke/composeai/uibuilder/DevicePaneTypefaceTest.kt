package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import ee.schimke.composeai.uibuilder.editor.ConstrainedFramePane
import ee.schimke.composeai.uibuilder.export.wearScreenUiBuilderDocument
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A device preview pane draws the design in its theme typefaces, as the canvas beside it does.
 *
 * The pane draws in a scene of its own, which starts with none of the editor's locals, and the
 * fonts were not among those it carried in: every device preview of a themed Wear screen was set in
 * the stock Wear face while the canvas showed the design's.
 */
@OptIn(ExperimentalTestApi::class)
class DevicePaneTypefaceTest {
  private val fontsDir: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "assets/rc-fonts") }
      .first { File(it, "fonts.json").isFile }

  private val document =
    wearScreenUiBuilderDocument("typefaces", JsonObject(emptyMap()), JsonObject(emptyMap())).let {
      base ->
      val root = base.roots.single()
      val scaffold = base.nodes.getValue(root)
      val themed =
        JsonObject(
          scaffold.properties +
            listOf(
                "themeDisplayTypeface",
                "themeTitleTypeface",
                "themeBodyTypeface",
                "themeLabelTypeface",
              )
              .associateWith {
                buildJsonObject {
                  put("type", "string")
                  put("value", JsonPrimitive("Lobster Two"))
                }
              }
        )
      base.copy(nodes = base.nodes + (root to scaffold.copy(properties = themed)))
    }

  private val lobster =
    FontFamily(
      Font("test:lobster", File(fontsDir, "LobsterTwo-Regular.ttf").readBytes(), FontWeight.Normal)
    )

  private fun drawPane(
    families: Map<String, FontFamily>
  ): List<androidx.compose.ui.graphics.Color> {
    var drawn = emptyList<androidx.compose.ui.graphics.Color>()
    runDesktopComposeUiTest(width = 300, height = 300) {
      setContent {
        MaterialTheme {
          WearCatalogAdapters {
            CompositionLocalProvider(LocalUiBuilderFontFamilies provides families) {
              Box(Modifier.testTag("pane")) {
                ConstrainedFramePane(
                  document = document,
                  widthDp = 227f,
                  heightDp = 227f,
                  scale = 1f,
                  densityRatio = 1f,
                )
              }
            }
          }
        }
      }
      waitForIdle()
      val pixels = onNodeWithTag("pane").captureToImage().toPixelMap()
      drawn = (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { pixels[it, y] } }
    }
    return drawn
  }

  @Test
  fun `a device pane draws the design's theme typeface`() {
    assertNotEquals(drawPane(emptyMap()), drawPane(mapOf("Lobster Two" to lobster)))
  }
}
