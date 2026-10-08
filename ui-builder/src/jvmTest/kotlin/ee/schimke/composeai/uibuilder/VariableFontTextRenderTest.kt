package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * `m3/variable-font-text`'s canvas stand-in: the text set in the chosen variable font, instanced at
 * the axes the design sets, as a production render's first frame draws it.
 */
@OptIn(ExperimentalTestApi::class)
class VariableFontTextRenderTest {
  private val vendored: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "assets/rc-fonts") }
      .first { File(it, "fonts.json").isFile }

  private val fonts =
    ProductionFontFamilies(
      readResource = { path ->
        File(vendored, path.removePrefix("/fonts/")).takeIf { it.isFile }?.readBytes()
      },
      googleFontsDirectory = null,
    )

  @Test
  fun `the weight axis makes the text heavier`() {
    val light = ink(draw(""""wght":{"type":"float","value":100}"""))
    val black = ink(draw(""""wght":{"type":"float","value":1000}"""))
    assertTrue(black > light * 3 / 2, "wght 1000 should be much heavier: $black against $light")
  }

  @Test
  fun `the width axis makes the text wider`() {
    val narrow = inkWidth(draw(""""wdth":{"type":"float","value":25}"""))
    val wide = inkWidth(draw(""""wdth":{"type":"float","value":151}"""))
    assertTrue(wide > narrow, "wdth 151 should be wider: $wide against $narrow")
  }

  @Test
  fun `the font is the one the design names`() {
    assertNotEquals(
      draw(""""font":{"type":"enum","value":"robotoFlex"}"""),
      draw(""""font":{"type":"enum","value":"googleSansFlex"}"""),
    )
  }

  @Test
  fun `roundness moves Google Sans Flex`() {
    val font = """"font":{"type":"enum","value":"googleSansFlex"}"""
    assertNotEquals(draw(font), draw("""$font,"rond":{"type":"float","value":100}"""))
  }

  private fun draw(properties: String): List<Color> {
    val document =
      decodeProductionRendererDocument(
        """{"schema":"compose-ui-builder-document/v1-candidate","id":"flex","title":"Flex","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":360,"heightDp":120,"density":1.0,"theme":"light","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},"stateVariables":{},"roots":["screen"],"nodes":{"screen":{"id":"screen","componentId":"m3/surface","properties":{},"modifiers":[],"slots":{"content":["title"]},"eventBindings":{}},"title":{"id":"title","componentId":"m3/variable-font-text","properties":{"text":{"type":"string","value":"Hamburg"},"fontSizeSp":{"type":"float","value":48},$properties},"modifiers":[],"slots":{},"eventBindings":{}}}}"""
      )
    var drawn = emptyList<Color>()
    runDesktopComposeUiTest(width = WIDTH, height = HEIGHT) {
      setContent {
        Box(Modifier.size(WIDTH.dp, HEIGHT.dp).testTag("frame")) {
          ProductionUiBuilderSurface(document, fonts)
        }
      }
      val pixels = onNodeWithTag("frame").captureToImage().toPixelMap()
      drawn = (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { pixels[it, y] } }
    }
    return drawn
  }

  /** Pixels unlike the frame's corner, which is the background: how much ink the text laid. */
  private fun ink(pixels: List<Color>): Int = pixels.count { it != pixels.first() }

  /** The span of columns holding any ink. */
  private fun inkWidth(pixels: List<Color>): Int {
    val background = pixels.first()
    val columns =
      pixels.indices.filter { pixels[it] != background }.map { it % WIDTH }.ifEmpty { listOf(0) }
    return columns.max() - columns.min()
  }

  private companion object {
    const val WIDTH = 360
    const val HEIGHT = 120
  }
}
