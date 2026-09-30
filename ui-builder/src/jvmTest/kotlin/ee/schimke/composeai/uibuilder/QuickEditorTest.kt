package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorChord
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.HOVER_EDITOR_WIDTH
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.editorShortcutFor
import ee.schimke.composeai.uibuilder.editor.hoverEditorPlacement
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import ee.schimke.composeai.uibuilder.export.toUiBuilderDocument
import ee.schimke.composeai.uibuilder.protocol.DesignDocumentV1
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
  fun `a collaborator deleting the selected node closes it, and any other change keeps it`() {
    val label = document.nodes.values.first { it.componentId == "m3/text" }
    val open =
      reducer.reduce(
        reducer.initial(document, selectedNodeId = label.id),
        UiBuilderEditorEvent.ShowQuickEditor,
      )

    assertTrue(reducer.reconciled(open, document.copy(revision = 999)).quickEditorOpen)

    val without =
      document.copy(
        revision = 999,
        nodes =
          (document.nodes - label.id).mapValues { (_, node) ->
            node.copy(slots = node.slots.mapValues { (_, ids) -> ids - label.id })
          },
      )
    val reconciled = reducer.reconciled(open, without)
    assertTrue(reconciled.selectedNodeId != label.id)
    assertFalse(reconciled.quickEditorOpen, "the card followed onto the fallback selection")
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
  fun `a canvas too narrow to hold it puts it beside the design over the next pane`() {
    val density = Density(1f)
    // A 380-wide canvas the design fills, with a Preview pane taking the 600 to its right.
    val workspace = Rect(100f, 0f, 480f, 800f)
    val designs = Rect(120f, 40f, 460f, 760f)
    val selected = UiBuilderPixelBounds(x = 140f, y = 200f, width = 80f, height = 40f)

    val placed =
      hoverEditorPlacement(
        workspace,
        designs,
        selected,
        density,
        380.dp,
        800.dp,
        roomLeft = 100.dp,
        roomRight = 600.dp,
      )
    assertTrue(
      workspace.left + placed.x.value >= designs.right,
      "placed over the design: $placed",
    )

    // With the window no wider than the canvas there is nowhere else, and the old fallback holds.
    val cramped = hoverEditorPlacement(workspace, designs, selected, density, 380.dp, 800.dp)
    assertTrue(workspace.left + cramped.x.value < designs.right, "fallback moved: $cramped")
  }

  @Test
  fun `beside a Preview pane it opens clear of the design and still takes typing`() =
    runDesktopComposeUiTest(width = 1200, height = 900) {
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            document,
            catalog,
            initialPanes = setOf(EditorPane.Editor, EditorPane.Preview),
            initialSelectedNodeId = "main-episode-title",
          )
        }
      }
      waitForIdle()
      // The canvas's copy of the cover, which is the leftmost: the Preview pane is to its right.
      val cover =
        onAllNodesWithContentDescription("Android Developers Backstage cover")
          .fetchSemanticsNodes()
          .minBy { it.boundsInRoot.left }
          .boundsInRoot
      window().performMouseInput { rightClick(cover.center) }
      waitForIdle()
      onAllNodesWithText("Quick edit").onFirst().performClick()
      waitForIdle()

      // Beside the design rather than over it.
      val card = onNodeWithContentDescription("Selection editor").getBoundsInRoot()
      assertTrue(card.left.value >= cover.right, "the card $card is over the design around $cover")

      // A popup that cannot take focus would show the field and never let it have the caret.
      val width = onNodeWithContentDescription("Width value")
      width.performMouseInput { click() }
      waitForIdle()
      width.assertIsFocused()
      width.performTextInput("5")
      waitForIdle()
      val typed = width.fetchSemanticsNode().config[SemanticsProperties.EditableText].text
      assertTrue(typed.endsWith("5"), "typing in the card did not reach its field: '$typed'")
    }

  @Test
  fun `a state-bound property is shown as its binding, not as a field to type over`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      val bound =
        Json.decodeFromString<DesignDocumentV1>(resource("/state-actions.uid"))
          .toUiBuilderDocument()
      val stateCatalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
      setContent {
        MaterialTheme {
          UiBuilderEditor(
            bound,
            stateCatalog,
            initialSelectedNodeId = "label",
            initialLayersOpen = true,
            initialCanvasZoom = 1f,
          )
        }
      }
      waitForIdle()
      // Opened the way the menu opens it: a right-click on the label's layer, then Quick edit.
      onNodeWithContentDescription("Select label").performMouseInput { rightClick() }
      waitForIdle()
      onAllNodesWithText("Quick edit").onFirst().performClick()
      waitForIdle()

      onNodeWithContentDescription("Selection editor").assertExists()
      onNodeWithContentDescription("Text bound to state label").assertExists()
      onNodeWithContentDescription("Text value").assertDoesNotExist()
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
      window().performMouseInput { click(DESIGN) }
      waitForIdle()
      onNodeWithContentDescription("Selection editor").assertDoesNotExist()
      window().performKeyInput { pressKey(Key.E) }
      waitForIdle()
      val card = onNodeWithContentDescription("Selection editor").getBoundsInRoot()

      // The gap between the design and the card is still the canvas.
      window().performMouseInput {
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
      window().performMouseInput {
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

  @Test
  fun `clicking another field commits the text and leaves the caret where it was clicked`() =
    runDesktopComposeUiTest(width = 1600, height = 1050) {
      setContent {
        MaterialTheme { UiBuilderEditor(document, catalog, chrome = PointerTestUiBuilderChrome) }
      }
      waitForIdle()
      val label = onAllNodesWithText("Podcast details").onFirst().getBoundsInRoot()
      window().performMouseInput {
        doubleClick(
          Offset(((label.left + label.right) / 2).toPx(), ((label.top + label.bottom) / 2).toPx())
        )
      }
      waitForIdle()
      onNodeWithContentDescription("Edit text in place").performTextReplacement("Show details")

      onNodeWithContentDescription("Property search").performClick()
      waitForIdle()

      onNodeWithContentDescription("Edit text in place").assertDoesNotExist()
      onAllNodesWithText("Show details").onFirst().assertExists()
      onNodeWithContentDescription("Property search").assertIsFocused()
    }

  private companion object {
    /** A point on the Jetcaster design as a 1600 × 1050 window draws it. */
    val DESIGN = Offset(420f, 600f)
  }

  /** The editor's own root: the quick editor is a popup, which is a second one. */
  private fun ComposeUiTest.window() = onAllNodes(isRoot()).onFirst()

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
