package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The undo offer in the running editor: a delete puts it up, its action brings the node back, and
 * an undo from the keyboard takes it down because there is nothing left for it to undo.
 */
@OptIn(ExperimentalTestApi::class)
class UndoSnackbarUiTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val deleted = "main-episode-title"
  private val title = "Episode 140: Lorem ipsum dolor"

  @Test
  fun `the snackbar's action undoes the delete it names`() =
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
      // Selected on the canvas, as a person would, which is also what hands the editor focus.
      onAllNodesWithText(title).onFirst().performClick()
      waitForIdle()
      assertEquals(deleted, state?.selectedNodeId)

      onRoot().performKeyInput { pressKey(Key.Delete) }
      waitForIdle()
      assertFalse(deleted in state!!.document.nodes)
      onNodeWithText("Deleted Text").assertExists()
      capture("deleted")

      onNodeWithText("Undo").performClick()
      waitForIdle()
      assertTrue(deleted in state!!.document.nodes)
      onNodeWithText("Deleted Text").assertDoesNotExist()
    }

  @Test
  fun `undoing from the keyboard takes the offer down`() =
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
      // Selected on the canvas, as a person would, which is also what hands the editor focus.
      onAllNodesWithText(title).onFirst().performClick()
      waitForIdle()
      assertEquals(deleted, state?.selectedNodeId)

      onRoot().performKeyInput { pressKey(Key.Delete) }
      waitForIdle()
      onNodeWithText("Deleted Text").assertExists()

      onRoot().performKeyInput {
        keyDown(Key.CtrlLeft)
        pressKey(Key.Z)
        keyUp(Key.CtrlLeft)
      }
      waitForIdle()
      assertTrue(deleted in state!!.document.nodes)
      onNodeWithText("Deleted Text").assertDoesNotExist()
    }

  @Test
  fun `an authoritative document takes the offer down`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      var generation by mutableStateOf(0)
      deleteThenAssertOffer {
        UiBuilderEditor(
          document = document,
          catalog = catalog,
          initialCanvasZoom = 1f,
          authoritativeGeneration = generation,
          onStateChanged = it,
        )
      }
      // The host answers with its own document — here the one before the delete, as a reconnect
      // that never saw it would. The rebuilt state has no delete to undo, so nothing may offer one.
      generation += 1
      waitForIdle()
      onNodeWithText("Deleted Text").assertDoesNotExist()
    }

  @Test
  fun `opening another design takes the offer down`() =
    runDesktopComposeUiTest(width = 1280, height = 800) {
      var shown by mutableStateOf(document)
      deleteThenAssertOffer {
        UiBuilderEditor(
          document = shown,
          catalog = catalog,
          initialCanvasZoom = 1f,
          onStateChanged = it,
        )
      }
      shown = document.copy(id = "${document.id}-other")
      waitForIdle()
      onNodeWithText("Deleted Text").assertDoesNotExist()
    }

  /** Mounts [editor], deletes the title from the canvas and checks the offer is up. */
  private fun ComposeUiTest.deleteThenAssertOffer(
    editor: @Composable ((UiBuilderEditorState) -> Unit) -> Unit
  ) {
    var state: UiBuilderEditorState? = null
    setContent { MaterialTheme { editor { state = it } } }
    waitForIdle()
    onAllNodesWithText(title).onFirst().performClick()
    waitForIdle()
    onRoot().performKeyInput { pressKey(Key.Delete) }
    waitForIdle()
    assertFalse(deleted in state!!.document.nodes)
    onNodeWithText("Deleted Text").assertExists()
  }

  private fun ComposeUiTest.capture(name: String) {
    val bitmap = onRoot().captureToImage().asSkiaBitmap()
    File(System.getProperty("uiBuilderProjectDir"), "build/undo-snackbar-evidence/$name.png")
      .apply {
        parentFile.mkdirs()
        writeBytes(
          requireNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
        )
      }
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
