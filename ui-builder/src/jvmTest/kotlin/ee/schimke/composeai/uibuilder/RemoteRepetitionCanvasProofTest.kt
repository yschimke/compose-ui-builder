package ee.schimke.composeai.uibuilder

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.unit.Density
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue

/** Cross-check the isolated export prototype against the editor's unchanged canvas. */
@OptIn(ExperimentalComposeUiApi::class)
class RemoteRepetitionCanvasProofTest {
  @Test
  fun `authored repeated component and expanded layouts draw identically`() {
    assumeTrue(System.getenv("VERIFY_REMOTE_REPETITION") == "true")
    val project = File(System.getProperty("uiBuilderProjectDir")).parentFile
    val input = File(project, "ui-builder-export/build/remote-json-repetition")
    val output = File(project, "ui-builder/build/repetition-proof").apply { mkdirs() }
    for (density in listOf(1, 2)) {
      fun render(suffix: String): androidx.compose.ui.graphics.PixelMap {
        val document =
          Json.decodeFromString<UiBuilderDocument>(
            File(input, "rows-$density.$suffix.json").readText()
          )
        return renderComposeScene(100 * density, 120 * density, Density(density.toFloat())) {
            UiBuilderSurface(document)
          }
          .toComposeImageBitmap()
          .toPixelMap()
      }
      val authored = render("document")
      val expanded = render("expanded")
      val image = BufferedImage(authored.width, authored.height, BufferedImage.TYPE_INT_ARGB)
      for (y in 0 until authored.height) for (x in 0 until authored.width) {
        assertEquals(authored[x, y], expanded[x, y], "density=$density pixel($x,$y)")
        image.setRGB(x, y, authored[x, y].toArgb())
      }
      ImageIO.write(image, "png", File(output, "canvas-$density.png"))
      val player =
        ImageIO.read(
          File(project, "experiments/remote-compose-poc/build/repetition-proof/rows-$density.png")
        )
      assertEquals(image.width, player.width)
      assertEquals(image.height, player.height)
      assertContentEquals(
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width),
        player.getRGB(0, 0, player.width, player.height, null, 0, player.width),
        "The editor canvas and compiled Remote document must match at density $density",
      )
      // Pin visible output as well: matching two empty canvases would not prove expansion.
      for (row in 0..2) {
        val red = authored[12 * density, (10 + row * 24) * density]
        val green = authored[(28 + row * 8) * density, (10 + row * 24) * density]
        assertTrue(red.red > .9f && red.green < .1f, "$red")
        assertTrue(green.green > .9f && green.red < .1f, "$green")
      }
    }
  }
}
