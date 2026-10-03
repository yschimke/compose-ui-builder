package ee.schimke.composeai.uibuilder

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * A production render — a design's thumbnail, its PNG export — draws in the design's typefaces from
 * the first frame, where it used to draw every one in the platform face.
 */
@OptIn(ExperimentalTestApi::class)
class ProductionUiBuilderFontsTest {
  private val vendored: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "assets/rc-fonts") }
      .first { File(it, "fonts.json").isFile }

  /** A Google Fonts cache holding [families], each as Lobster Two so it is unmistakable. */
  private fun cache(vararg families: String): File =
    createTempDirectory("google-fonts").toFile().also { dir ->
      families.forEach { File(vendored, "LobsterTwo-Regular.ttf").copyTo(File(dir, "$it-400.ttf")) }
    }

  private fun fonts(directory: File?) =
    ProductionFontFamilies(
      readResource = { path ->
        File(vendored, path.removePrefix("/fonts/")).takeIf { it.isFile }?.readBytes()
      },
      googleFontsDirectory = directory,
    )

  @Test
  fun `a vendored family resolves from the classpath by any spelling a document uses`() {
    val fonts = fonts(directory = null)
    assertNotNull(fonts["Orbitron"])
    assertNotNull(fonts["google:orbitron"])
    assertNotNull(fonts["serif"])
  }

  @Test
  fun `any other family resolves from the host's cache by the server's file name`() {
    val fonts = fonts(cache("michroma", "exo-2"))
    assertNotNull(fonts["Michroma"])
    assertNotNull(fonts["google:Exo 2"])
    assertEquals(setOf("Michroma", "google:Exo 2"), fonts.keys)
  }

  @Test
  fun `a family with no file stays on the platform face`() {
    assertNull(fonts(directory = null)["Michroma"])
    val fonts = fonts(cache("michroma"))
    assertNull(fonts["Unbounded"])
    assertFalse("Unbounded" in fonts)
  }

  @Test
  fun `the cache slug is the server's`() {
    assertEquals("exo-2", ProductionFontFamilies.googleFontSlug("Exo 2"))
    assertEquals("major-mono-display", ProductionFontFamilies.googleFontSlug("Major  Mono Display"))
  }

  @Test
  fun `a theme host's display typeface is drawn in the first frame`() {
    val document =
      decodeProductionRendererDocument(
        """{"schema":"compose-ui-builder-document/v1-candidate","id":"themed","title":"Themed","revision":0,"catalogPin":{"systemId":"m3-catalog","catalogRevision":"candidate","capabilityDigest":"candidate","nativeRuntimeId":"candidate"},"environment":{"widthDp":360,"heightDp":200,"density":1.0,"theme":"light","locale":"en-US","fontScale":1.0,"layoutDirection":"ltr"},"stateVariables":{},"roots":["screen"],"nodes":{"screen":{"id":"screen","componentId":"m3/surface","properties":{"themeDisplayTypeface":{"type":"string","value":"Michroma"}},"modifiers":[],"slots":{"content":["title"]},"eventBindings":{}},"title":{"id":"title","componentId":"m3/text","properties":{"text":{"type":"string","value":"Aurora"},"style":{"type":"enum","value":"displayMedium"}},"modifiers":[],"slots":{},"eventBindings":{}}}}"""
      )

    fun draw(fonts: Map<String, FontFamily>): List<androidx.compose.ui.graphics.Color> {
      var drawn = emptyList<androidx.compose.ui.graphics.Color>()
      runDesktopComposeUiTest(width = 360, height = 200) {
        setContent {
          Box(Modifier.size(360.dp, 200.dp).testTag("frame")) {
            ProductionUiBuilderSurface(document, fonts)
          }
        }
        val pixels = onNodeWithTag("frame").captureToImage().toPixelMap()
        drawn =
          (0 until pixels.height).flatMap { y -> (0 until pixels.width).map { pixels[it, y] } }
      }
      return drawn
    }

    assertNotEquals(draw(emptyMap()), draw(fonts(cache("michroma"))))
  }
}
