package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * Alt-hover redlines in the running editor: with a node selected, holding Alt over another node
 * draws the gaps between them, and letting go of Alt takes them away. The document never changes.
 */
@OptIn(ExperimentalTestApi::class)
class SpacingMeasureUiTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val title = "Episode 140: Lorem ipsum dolor"

  @Test
  fun `holding Alt over another node measures it against the selection`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      var state: UiBuilderEditorState? = null
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document = document,
            catalog = catalog,
            initialCanvasZoom = 1f,
            onStateChanged = { state = it },
          )
        }
      }
      waitForIdle()
      // Before the selection, the canvas holds the only copy of the title; the breadcrumb and the
      // inspector name it once it is selected, so it is pinned by where it was drawn.
      val titleBounds = onAllNodesWithText(title).onFirst().fetchSemanticsNode().boundsInRoot
      onRoot().performMouseInput { click(titleBounds.center) }
      waitForIdle()
      assertEquals("main-episode-title", state?.selectedNodeId)
      val before = state!!.document

      // Hovering without Alt measures nothing.
      onRoot().performMouseInput { moveTo(beside(titleBounds, 44f)) }
      waitForIdle()
      assertTrue(measurements().isEmpty())

      onRoot().performKeyInput { keyDown(Key.AltLeft) }
      // Right of the title, clear of its resize handle: the episode artwork beside it.
      onRoot().performMouseInput { moveTo(beside(titleBounds, 45f)) }
      waitForIdle()
      val measured = measurements()
      assertEquals(1, measured.size, "one overlay while Alt is held")
      assertTrue(
        measured.single().endsWith("dp") && "none" !in measured.single(),
        measured.single(),
      )
      capture("alt-hover")

      // Over the selection itself, it is measured against its parent instead.
      onRoot().performMouseInput { moveTo(titleBounds.center) }
      waitForIdle()
      assertTrue(measurements().single().startsWith("Spacing to main-episode-"))
      capture("alt-hover-parent")

      // Letting go of Alt, without moving, takes it away.
      onRoot().performKeyInput { keyUp(Key.AltLeft) }
      waitForIdle()
      assertTrue(measurements().isEmpty())
      assertEquals(before, state!!.document, "measuring never edits the document")
    }

  private fun beside(bounds: Rect, by: Float) =
    Offset(bounds.right + by, bounds.top + bounds.height * 0.85f)

  private fun ComposeUiTest.measurements(): List<String> =
    onAllNodesWithContentDescription("Spacing to ", substring = true, useUnmergedTree = true)
      .fetchSemanticsNodes()
      .flatMap { node ->
        node.config.getOrElse(
          androidx.compose.ui.semantics.SemanticsProperties.ContentDescription
        ) {
          emptyList()
        }
      }

  private fun ComposeUiTest.capture(name: String) {
    val bitmap = onRoot().captureToImage().asSkiaBitmap()
    File(System.getProperty("uiBuilderProjectDir"), "build/alt-measure-evidence/$name.png").apply {
      parentFile.mkdirs()
      writeBytes(
        requireNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
      )
    }
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
