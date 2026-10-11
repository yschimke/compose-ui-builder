package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The editor itself: two colours given to one text, then the second text matched to the first from
 * the "Recent colours" row in one press. The screenshot it writes is the evidence a reviewer looks
 * at.
 */
@OptIn(ExperimentalTestApi::class)
class RecentColoursInspectorTest {
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
  fun `a colour committed on one text is one press away on the next`() =
    runDesktopComposeUiTest(width = 1600, height = 1000) {
      var latest: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            chrome = PointerTestUiBuilderChrome,
            initialSelectedNodeId = "main-episode-title",
            initialInspectorOpen = true,
            initialLayersOpen = true,
            initialCanvasZoom = 1f,
            onStateChanged = { latest = it },
          )
        }
      }
      waitForIdle()
      addColorProperty()
      // Nothing committed yet, so no row to read past.
      onNodeWithText("Recent colours").assertDoesNotExist()

      onNodeWithContentDescription("Use #FF1A73E8 for color").performScrollTo().performClick()
      waitForIdle()
      onNodeWithContentDescription("Use primary for color").performScrollTo().performClick()
      waitForIdle()
      onNodeWithText("Recent colours").assertExists()
      // Newest first, and the current value is the selected one.
      onNodeWithContentDescription("Use recent primary for color").assertIsSelected()
      onNodeWithContentDescription("Use recent #FF1A73E8 for color").assertExists()

      // Filtered rather than scrolled to: the layer sits deep in a collapsed tree.
      onNodeWithContentDescription("Component catalog search")
        .performTextReplacement("main-episode-podcast")
      waitForIdle()
      onNodeWithContentDescription("Select main-episode-podcast").performMouseInput { click() }
      waitForIdle()
      addColorProperty()
      onNodeWithContentDescription("Use recent #FF1A73E8 for color")
        .performScrollTo()
        .performClick()
      waitForIdle()
      onNodeWithContentDescription("Color property").assertTextEquals("#FF1A73E8")
      val podcast = assertNotNull(latest).document.nodes.getValue("main-episode-podcast")
      assertEquals(
        "#FF1A73E8",
        (podcast.properties["color"] as JsonObject)["value"]?.jsonPrimitive?.content,
      )
      onNodeWithContentDescription("Use recent #FF1A73E8 for color").assertIsSelected()
      capture("recent-colours")
    }

  private fun ComposeUiTest.addColorProperty() {
    onNodeWithContentDescription("Property search").performTextReplacement("color")
    // A text that already has a colour shows its control without being added.
    val add = onAllNodesWithContentDescription("Add Color property")
    if (add.fetchSemanticsNodes().isNotEmpty()) add.onFirst().performClick()
    waitForIdle()
    onNodeWithContentDescription("Color property").assertExists()
  }

  private fun ComposeUiTest.capture(name: String) {
    val root = File(System.getProperty("uiBuilderProjectDir"), "..")
    // The window's root, not the recent swatch's tooltip, which is a second root while hovered.
    val screenshot = onAllNodes(isRoot()).onFirst().captureToImage()
    File(root, "ui-builder/build/recent-colours-evidence/$name.png").apply {
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
