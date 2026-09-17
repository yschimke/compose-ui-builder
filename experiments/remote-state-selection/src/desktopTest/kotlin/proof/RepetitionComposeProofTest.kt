package proof

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*
import proof.repetition.compose.ComposeRepeatedContent

@OptIn(ExperimentalTestApi::class)
class RepetitionComposeProofTest {
  @Test fun functionsAndLoopsAtDensityOne() = verify(1)

  @Test fun functionsAndLoopsAtDensityTwo() = verify(2)

  private fun verify(density: Int) =
    runDesktopComposeUiTest(width = 100 * density, height = 120 * density) {
      setContent {
        CompositionLocalProvider(LocalDensity provides Density(density.toFloat())) {
          Box(Modifier.size(100.dp, 120.dp).testTag("frame")) { ComposeRepeatedContent() }
        }
      }
      val root = File(System.getProperty("uiBuilderProjectDir")).parentFile
      val output =
        File(root, "experiments/remote-state-selection/build/repetition-source-evidence").apply {
          mkdirs()
        }
      val frame = onNodeWithTag("frame")
      fun capture(name: String): BufferedImage {
        mainClock.advanceTimeBy(1000)
        waitForIdle()
        val pixels = frame.captureToImage().toPixelMap()
        val image = BufferedImage(pixels.width, pixels.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) image.setRGB(
          x,
          y,
          pixels[x, y].toArgb(),
        )
        ImageIO.write(image, "png", File(output, "$name-$density.png"))
        return image
      }
      val actual = capture("compose-initial")
      val expected =
        ImageIO.read(
          File(root, "docs/design/evidence/ui-builder-repetition-proof/rows-$density.png")
        )
      assertContentEquals(
        expected.getRGB(0, 0, expected.width, expected.height, null, 0, expected.width),
        actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width),
        "Generated Compose and Remote JSON layout must match every pixel",
      )
      for (row in 0..2) {
        for ((name, x, color) in
          listOf(
            Triple("green", 28 + row * 8, 0xff00ff00.toInt()),
            Triple("red", 12, 0xffff0000.toInt()),
          )) {
          frame.performTouchInput {
            click(Offset(x.toFloat() * density, (10f + row * 24) * density))
          }
          val image = capture("compose-row-$row-$name")
          assertEquals(color, image.getRGB(30 * density, 78 * density), "$name click in row $row")
        }
      }
    }
}
