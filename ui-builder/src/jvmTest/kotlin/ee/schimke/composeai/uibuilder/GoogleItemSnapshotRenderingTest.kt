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
    for (app in GOOGLE_APPS) {
      val source = snapshot(app)
      val protocol = json.decodeFromString<DesignDocumentV1>(source)
      assertTrue(protocol.components.isNotEmpty(), "$app must exercise reusable components")
      val documents = listOf(source, projectRendererDocument(protocol))
      val images = documents.mapIndexed { index, content ->
        var pixels = intArrayOf()
        runDesktopComposeUiTest(width = 1280, height = 800) {
          val document = json.decodeFromString<UiBuilderDocument>(content)
          setContent { MaterialTheme { UiBuilderSurface(document, editorOverlay = false) } }
          mainClock.advanceTimeBy(1000)
          assertNoUnsupportedComponent("$app (${if (index == 0) "saved" else "projected"})")
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

  /**
   * The designs page's thumbnails, as the server draws them: the projected document decoded and
   * drawn by the packaged daemon's own entry point, with its pinned catalog's canvas vocabulary.
   *
   * The comparison above cannot catch a placement that both paths lose — two identical pictures of
   * "Unsupported component: design/component-instance → (none)" are still identical, which is what
   * every card on preview.coo.ee's designs page showed for these items — so this asks the tree.
   */
  @Test
  fun `reusable Google items draw their bodies through the production render entry point`() {
    val json = Json { ignoreUnknownKeys = true }
    for (app in GOOGLE_APPS) {
      val projected =
        projectRendererDocument(json.decodeFromString<DesignDocumentV1>(snapshot(app)))
      runDesktopComposeUiTest(width = 1280, height = 800) {
        val document = decodeProductionRendererDocument(projected)
        setContent { MaterialTheme { ProductionUiBuilderSurface(document) } }
        mainClock.advanceTimeBy(1000)
        assertNoUnsupportedComponent("$app (production)")
      }
    }
  }

  private fun ComposeUiTest.assertNoUnsupportedComponent(label: String) {
    val unsupported =
      onAllNodesWithText("Unsupported component", substring = true, useUnmergedTree = true)
        .fetchSemanticsNodes()
    assertTrue(
      unsupported.isEmpty(),
      "$label drew ${unsupported.size} Unsupported component diagnostics",
    )
  }

  private fun snapshot(app: String): String =
    File("../docs/design/live-snapshots/google-$app-tablet.json").readText()

  private companion object {
    val GOOGLE_APPS = listOf("gmail", "calendar", "photos", "keep", "play")
  }
}
