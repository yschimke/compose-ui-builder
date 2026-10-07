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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A text's font settings change what is drawn: `wdth` narrows a variable face, `frac` sets
 * fractions, and both are drawn in a production render's first frame, from the same vendored files
 * the editor's registry reads.
 */
@OptIn(ExperimentalTestApi::class)
class TextFontSettingsRenderTest {
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
  fun `a width axis narrows the variable face the text is set in`() {
    val regular = inkWidth(draw("Roboto Flex", "Hamburg", variations = null))
    val narrow = inkWidth(draw("Roboto Flex", "Hamburg", variations = "'wdth' 25"))
    val wide = inkWidth(draw("Roboto Flex", "Hamburg", variations = "'wdth' 151"))
    assertTrue(narrow < regular, "wdth 25 should be narrower: $narrow against $regular")
    assertTrue(wide > regular, "wdth 151 should be wider: $wide against $regular")
  }

  @Test
  fun `an axis the face lacks draws the face unchanged`() {
    assertEquals(
      draw("Inter", "Hamburg", variations = null),
      draw("Inter", "Hamburg", variations = "'wdth' 25"),
    )
  }

  @Test
  fun `a feature reshapes a static face's text`() {
    // `frac` turns 1/2 into one fraction glyph, which no amount of spacing could imitate.
    assertNotEquals(
      draw("Inter", "1/2 3/4", features = null),
      draw("Inter", "1/2 3/4", features = "'frac'"),
    )
  }

  private fun draw(
    family: String,
    text: String,
    variations: String? = null,
    features: String? = null,
  ): List<Color> {
    val settings = buildString {
      variations?.let { append(""","fontVariationSettings":{"type":"string","value":"$it"}""") }
      features?.let { append(""","fontFeatureSettings":{"type":"string","value":"$it"}""") }
    }
    val document =
      decodeProductionRendererDocument(
        """{"schema":"compose-ui-builder-document/v1-candidate","id":"axes","title":"Axes","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":360,"heightDp":120,"density":1.0,"theme":"light","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},"stateVariables":{},"roots":["screen"],"nodes":{"screen":{"id":"screen","componentId":"m3/surface","properties":{"themeDisplayTypeface":{"type":"string","value":"$family"}},"modifiers":[],"slots":{"content":["title"]},"eventBindings":{}},"title":{"id":"title","componentId":"m3/text","properties":{"text":{"type":"string","value":"$text"},"style":{"type":"enum","value":"displayMedium"}$settings},"modifiers":[],"slots":{},"eventBindings":{}}}}"""
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

  /** The span of columns holding any pixel unlike the frame's corner, which is the background. */
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
