package ee.schimke.composeai.uibuilder

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.DesktopComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import ee.schimke.composeai.uibuilder.capability.CapabilityCatalogParser
import ee.schimke.composeai.uibuilder.editor.EditorCatalogItem
import ee.schimke.composeai.uibuilder.editor.EditorComponentKind
import ee.schimke.composeai.uibuilder.editor.QuickWrapChoice
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditor
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorReducer
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorState
import ee.schimke.composeai.uibuilder.editor.WrapChooserKeyResult
import ee.schimke.composeai.uibuilder.editor.quickWrapChoices
import ee.schimke.composeai.uibuilder.editor.wrapChooserKey
import ee.schimke.composeai.uibuilder.export.UiBuilderReducer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * The wrap chord's chooser: it offers only the quick containers the reducer would accept, its keys
 * pick by highlight or by digit, and the wrap it dispatches is one undo step.
 */
@OptIn(ExperimentalTestApi::class)
class WrapChooserTest {
  private val catalog = CapabilityCatalogParser.parse(resource("/m3-catalog-capabilities-v1.json"))
  private val reducer = UiBuilderEditorReducer(catalog)
  private val document =
    UiBuilderReducer.replay(
        Json.parseToJsonElement(resource("/jetcaster-discover-operations-v1.json")).jsonObject
      )
      .document
  private val title = "Episode 140: Lorem ipsum dolor"
  private val wrapped = "main-episode-title"

  private fun item(id: String, name: String) =
    EditorCatalogItem(id, name, EditorComponentKind.Container, group = "Layout")

  @Test
  fun `only the quick containers the wrap check accepts are offered, each keeping its digit`() {
    // A selection a Column cannot hold still keeps Row on 2, so the sequence a hand learns holds.
    val choices =
      quickWrapChoices(
        listOf(item("m3/card", "Card"), item("layout/box", "Box"), item("layout/row", "Row"))
      )
    assertEquals(
      listOf(2 to "layout/row", 3 to "layout/box"),
      choices.map { it.digit to it.item.componentId },
    )
  }

  @Test
  fun `the chooser draws from the same candidates the node menu's Wrap in uses`() {
    val single = reducer.initial(document, selectedNodeId = wrapped)
    val candidates = reducer.wrapCandidates(single).map { it.componentId }
    val offered = quickWrapChoices(reducer.wrapCandidates(single))
    assertTrue(offered.isNotEmpty(), "a text in a column can go in a Column, Row or Box")
    assertTrue(offered.all { it.item.componentId in candidates })
    // Nothing selected, nothing offered: the chord says so rather than opening an empty list.
    assertEquals(emptyList(), quickWrapChoices(reducer.wrapCandidates(reducer.initial(document))))
  }

  @Test
  fun `arrows move the highlight, Enter and digits pick, Esc closes`() {
    val choices =
      listOf(
        QuickWrapChoice(1, item("layout/column", "Column")),
        QuickWrapChoice(3, item("layout/box", "Box")),
      )
    assertEquals(WrapChooserKeyResult.Highlight(1), wrapChooserKey(Key.DirectionDown, choices, 0))
    assertEquals(WrapChooserKeyResult.Highlight(0), wrapChooserKey(Key.DirectionDown, choices, 1))
    assertEquals(WrapChooserKeyResult.Highlight(1), wrapChooserKey(Key.DirectionUp, choices, 0))
    assertEquals(WrapChooserKeyResult.Pick(choices[1]), wrapChooserKey(Key.Enter, choices, 1))
    assertEquals(WrapChooserKeyResult.Pick(choices[1]), wrapChooserKey(Key.Three, choices, 0))
    assertEquals(WrapChooserKeyResult.Pick(choices[0]), wrapChooserKey(Key.NumPad1, choices, 1))
    // Row is not on offer here, so 2 does nothing rather than picking the second row.
    assertEquals(WrapChooserKeyResult.Ignored, wrapChooserKey(Key.Two, choices, 0))
    assertEquals(WrapChooserKeyResult.Dismiss, wrapChooserKey(Key.Escape, choices, 0))
  }

  @Test
  fun `the chord, a digit, and one undo`() =
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
      onAllNodesWithText(title).onFirst().performClick()
      waitForIdle()
      assertEquals(wrapped, state?.selectedNodeId)
      val before = state!!.document

      onRoot().performKeyInput {
        keyDown(Key.CtrlLeft)
        keyDown(Key.AltLeft)
        pressKey(Key.G)
        keyUp(Key.AltLeft)
        keyUp(Key.CtrlLeft)
      }
      waitForIdle()
      onNodeWithText("Wrap in").assertExists()
      capture("chooser")

      // The digit goes to the chooser, which has focus, not to the editor underneath.
      onNodeWithText("Wrap in").performKeyInput { pressKey(Key.Two) }
      waitForIdle()
      onNodeWithText("Wrap in").assertDoesNotExist()
      assertIs<CommandOutcome.Accepted>(state!!.lastOutcome)
      val container = assertNotNull(state!!.selectedNodeId)
      assertEquals("layout/row", state!!.document.nodes.getValue(container).componentId)
      assertEquals(
        listOf(wrapped),
        state!!.document.nodes.getValue(container).slots.values.flatten(),
      )

      // One step: a single undo puts the document back as it was.
      onRoot().performKeyInput {
        keyDown(Key.CtrlLeft)
        pressKey(Key.Z)
        keyUp(Key.CtrlLeft)
      }
      waitForIdle()
      assertEquals(before.nodes, state!!.document.nodes)
    }

  @Test
  fun `undoing a chooser wrap from the reducer restores the document`() {
    val single = reducer.initial(document, selectedNodeId = wrapped)
    val choice = quickWrapChoices(reducer.wrapCandidates(single)).first()
    val applied =
      reducer.reduce(single, UiBuilderEditorEvent.WrapSelection(choice.item.componentId))
    assertIs<CommandOutcome.Accepted>(applied.lastOutcome)
    val undone = reducer.reduce(applied, UiBuilderEditorEvent.Undo)
    assertEquals(single.document.nodes, undone.document.nodes)
  }

  private fun DesktopComposeUiTest.capture(name: String) {
    // The whole scene, not `onRoot()`: the chooser is a dialog in a layer of its own, and the
    // editor's root alone would leave it out.
    val bitmap = captureToImage().asSkiaBitmap()
    File(System.getProperty("uiBuilderProjectDir"), "build/wrap-shortcut-evidence/$name.png")
      .apply {
        parentFile.mkdirs()
        writeBytes(
          requireNotNull(Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG)).bytes
        )
      }
  }

  private fun resource(path: String): String = checkNotNull(javaClass.getResource(path)).readText()
}
