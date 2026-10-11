package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.*
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The editor itself, after two Adds from the palette: the "Recently used" shelf appears at the top
 * of the panel with the last component first. The screenshots it writes are the evidence a reviewer
 * looks at.
 */
@OptIn(ExperimentalTestApi::class)
class RecentComponentsPaletteTest {
  private val catalog =
    CapabilityCatalogParser.parse(
      checkNotNull(javaClass.getResource("/m3-catalog-capabilities-v1.json")).readText()
    )
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/jetcaster-discover-operations-v1.json")).readText()
          )
          .jsonObject
      )
      .document

  @Test
  fun `adding from the palette puts the component on the recently used shelf`() =
    runDesktopComposeUiTest(width = 1600, height = 1200) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialSelectedNodeId = "discover-grid",
            initialComponentsOpen = true,
          )
        }
      }
      waitForIdle()
      capture("before")
      onNodeWithContentDescription("Collapse Recently used").assertDoesNotExist()

      // Two components from the first shelves, so the panel need not scroll to reach them.
      onAllNodesWithContentDescription("Add Box").onFirst().performClick()
      waitForIdle()
      onAllNodesWithContentDescription("Add Column").onFirst().performClick()
      waitForIdle()
      onNodeWithContentDescription("Collapse Recently used").assertExists()
      // Two copies of each now: the shelf's, and the component's own.
      onAllNodesWithContentDescription("Add Column").assertCountEquals(2)
      capture("after")
    }

  private fun ComposeUiTest.capture(name: String) {
    val root = File(System.getProperty("uiBuilderProjectDir"), "..")
    val screenshot = onRoot().captureToImage()
    File(root, "ui-builder/build/recent-components-evidence/$name.png").apply {
      parentFile.mkdirs()
      writeBytes(
        checkNotNull(
            Image.makeFromBitmap(screenshot.asSkiaBitmap()).encodeToData(EncodedImageFormat.PNG)
          )
          .bytes
      )
    }
  }
}
