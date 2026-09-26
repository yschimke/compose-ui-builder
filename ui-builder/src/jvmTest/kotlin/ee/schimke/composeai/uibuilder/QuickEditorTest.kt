package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorChord
import ee.schimke.composeai.uibuilder.editor.HOVER_EDITOR_WIDTH
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.editorShortcutFor
import ee.schimke.composeai.uibuilder.editor.hoverEditorPlacement
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.renderer.sdk.UiBuilderPixelBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The selection's quick editor: the small card of a node's values that used to open, beside the
 * node and over the design, every time something was selected.
 *
 * It is now there when asked for — `E`, or Quick edit on the selection menu — opens in the empty
 * workspace beside the design, moves by its title row, and goes away on `Esc`, its close control, a
 * press anywhere else on the canvas, or the selection moving on.
 */
@OptIn(ExperimentalTestApi::class)
class QuickEditorTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document

  @Test
  fun `closed until asked for, and closed again when the selection moves on`() {
    val start = reducer.initial(document, selectedNodeId = "discover-grid")
    assertFalse(start.quickEditorOpen, "the quick editor opens by itself")

    val open = reducer.reduce(start, UiBuilderEditorEvent.ToggleQuickEditor)
    assertTrue(open.quickEditorOpen)

    val other = document.nodes.keys.first { it != "discover-grid" }
    val moved = reducer.reduce(open, UiBuilderEditorEvent.SelectNode(other))
    assertFalse(moved.quickEditorOpen, "a new selection kept the quick editor open")

    val reopened = reducer.reduce(moved, UiBuilderEditorEvent.ShowQuickEditor)
    assertTrue(reducer.reduce(reopened, UiBuilderEditorEvent.SelectNode(other)).quickEditorOpen)
    assertFalse(reducer.reduce(reopened, UiBuilderEditorEvent.HideQuickEditor).quickEditorOpen)
    assertFalse(reducer.reduce(reopened, UiBuilderEditorEvent.ToggleQuickEditor).quickEditorOpen)
  }

  @Test
  fun `E toggles it and Esc closes it`() {
    assertEquals(
      UiBuilderEditorEvent.ToggleQuickEditor,
      editorShortcutFor(EditorChord(Key.E, command = false, shift = false))?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.HideQuickEditor,
      editorShortcutFor(EditorChord(Key.Escape, command = false, shift = false))?.event,
    )
  }

  @Test
  fun `it opens beside the design, not over it`() {
    val density = Density(1f)
    val workspace = Rect(0f, 0f, 1200f, 800f)
    val designs = Rect(300f, 40f, 700f, 760f)
    val selected = UiBuilderPixelBounds(x = 320f, y = 400f, width = 360f, height = 72f)

    val right = hoverEditorPlacement(workspace, designs, selected, density, 1200.dp, 800.dp)
    assertTrue(right.x.value >= designs.right, "placed over the design: $right")
    assertEquals(selected.y, right.y.value)

    // No room on the right: the left side of the design.
    val wide = Rect(300f, 40f, 1000f, 760f)
    val left = hoverEditorPlacement(workspace, wide, selected, density, 1200.dp, 800.dp)
    assertTrue(
      left.x.value + HOVER_EDITOR_WIDTH.value <= wide.left,
      "placed over the design: $left",
    )
  }

  @Test
  fun `the real editor opens it on E and closes it on a press elsewhere`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme { UiBuilderEditor(document, catalog, chrome = PointerTestUiBuilderChrome) }
      }
      waitForIdle()
      onNodeWithContentDescription("Selection editor").assertDoesNotExist()

      // A press on the design first: it selects what is under it and gives the editor the keys,
      // which is how anyone gets here.
      onRoot().performMouseInput { click(DESIGN) }
      waitForIdle()
      onNodeWithContentDescription("Selection editor").assertDoesNotExist()
      onRoot().performKeyInput { pressKey(Key.E) }
      waitForIdle()
      val card = onNodeWithContentDescription("Selection editor").getBoundsInRoot()

      // The gap between the design and the card is still the canvas.
      onRoot().performMouseInput {
        click(Offset((card.left - 8.dp).toPx(), (card.top + 8.dp).toPx()))
      }
      waitForIdle()
      onNodeWithContentDescription("Selection editor").assertDoesNotExist()
    }

  @Test
  fun `a label's text can be typed over in place, and a container's cannot`() {
    val state = reducer.initial(document, selectedNodeId = "discover-grid")
    val label =
      document.nodes.values.first { node ->
        node.componentId == "m3/text" && reducer.inlineText(state, node.id) == "Podcast details"
      }
    assertEquals("Podcast details", reducer.inlineText(state, label.id))
    assertEquals(null, reducer.inlineText(state, "discover-grid"))
  }

  @Test
  fun `double-clicking a label on the canvas types over it in place`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme { UiBuilderEditor(document, catalog, chrome = PointerTestUiBuilderChrome) }
      }
      waitForIdle()
      // Not selected yet: the first click of the two selects it and opens Properties, which
      // re-fits the canvas under the pointer before the second click lands.
      val label = onAllNodesWithText("Podcast details").onFirst().getBoundsInRoot()
      onRoot().performMouseInput {
        doubleClick(
          Offset(((label.left + label.right) / 2).toPx(), ((label.top + label.bottom) / 2).toPx())
        )
      }
      waitForIdle()

      onNodeWithContentDescription("Edit text in place").performTextReplacement("Show details")
      onNodeWithContentDescription("Edit text in place").performKeyInput { pressKey(Key.Enter) }
      waitForIdle()

      onNodeWithContentDescription("Edit text in place").assertDoesNotExist()
      onAllNodesWithText("Show details").onFirst().assertExists()
      onAllNodesWithText("Podcast details").assertCountEquals(0)
      // Typing over a label is not a request for its properties.
      onNodeWithContentDescription("Selection editor").assertDoesNotExist()
    }

  private companion object {
    /** A point on the Jetcaster design as a 1600 × 1050 window draws it. */
    val DESIGN = Offset(420f, 600f)
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
