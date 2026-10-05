package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.canvas.UiBuilderSurface
import ee.schimke.composeai.uibuilder.export.UiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
import ee.schimke.composeai.uibuilder.service.projectRendererDocument
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** Real saved documents exercise both editor decoding and the PNG/SVG render-port projection. */
@OptIn(ExperimentalTestApi::class)
class GoogleItemSnapshotRenderingTest {
  @Test
  fun `reusable Google items render identically through editor and export projection`() {
    val json = Json { ignoreUnknownKeys = true }
    for (app in listOf("gmail", "calendar", "photos", "keep", "play")) {
      val source = File("../docs/design/live-snapshots/google-$app-tablet.json").readText()
      val protocol = json.decodeFromString<DesignDocumentV1>(source)
      assertTrue(protocol.components.isNotEmpty(), "$app must exercise reusable components")
      val documents = listOf(source, projectRendererDocument(protocol))
      val images = documents.mapIndexed { index, content ->
        var pixels = intArrayOf()
        runDesktopComposeUiTest(width = 1280, height = 800) {
          val document = json.decodeFromString<UiBuilderDocument>(content)
          setContent { MaterialTheme { UiBuilderSurface(document, editorOverlay = false) } }
          mainClock.advanceTimeBy(1000)
          val image = onRoot().captureToImage().toAwtImage()
          val output = File("build/reports/google-items/$app-$index.png")
          output.parentFile.mkdirs()
          ImageIO.write(image, "png", output)
          pixels = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        }
        pixels
      }
      assertEquals(images[0].size, images[1].size)
      assertEquals(
        0,
        images[0].indices.count { images[0][it] != images[1][it] },
        "$app changed pixels",
      )
    }
  }
}
