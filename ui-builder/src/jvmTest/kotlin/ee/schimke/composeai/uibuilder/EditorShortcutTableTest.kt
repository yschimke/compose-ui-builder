package ee.schimke.composeai.uibuilder

import androidx.compose.ui.input.key.Key
import ee.schimke.composeai.uibuilder.editor.CanvasZoomCommand
import ee.schimke.composeai.uibuilder.editor.EDITOR_GESTURES
import ee.schimke.composeai.uibuilder.editor.EDITOR_SHORTCUTS
import ee.schimke.composeai.uibuilder.editor.EditorChord
import ee.schimke.composeai.uibuilder.editor.EditorMoveDirection
import ee.schimke.composeai.uibuilder.editor.EditorPane
import ee.schimke.composeai.uibuilder.editor.EditorSelectionMove
import ee.schimke.composeai.uibuilder.editor.EditorViewCommand
import ee.schimke.composeai.uibuilder.editor.UiBuilderEditorEvent
import ee.schimke.composeai.uibuilder.editor.editorShortcutFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shortcut table is both the handler's rules and the panel's contents, so the thing worth
 * testing is that those cannot disagree.
 */
class EditorShortcutTableTest {
  private fun resolve(chord: EditorChord) = editorShortcutFor(chord)

  @Test
  fun `every advertised shortcut is reachable`() {
    // Order is behaviour here: an entry placed below one that already claims its chord would never
    // fire, and the panel would still advertise it. This is the test that stops that happening.
    EDITOR_SHORTCUTS.forEachIndexed { index, shortcut ->
      shortcut.keys.forEach { key ->
        val chord = EditorChord(key, shortcut.command, shortcut.shift ?: false)
        assertEquals(
          index,
          EDITOR_SHORTCUTS.indexOfFirst { it.matches(chord) },
          "${shortcut.chord} (${shortcut.description}) is shadowed by an earlier entry",
        )
      }
    }
  }

  @Test
  fun `redo claims the shifted spelling before undo sees it`() {
    assertEquals(
      UiBuilderEditorEvent.Redo,
      resolve(EditorChord(Key.Z, command = true, shift = true))?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.Undo,
      resolve(EditorChord(Key.Z, command = true, shift = false))?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.Redo,
      resolve(EditorChord(Key.Y, command = true, shift = false))?.event,
    )
  }

  @Test
  fun `reordering claims the modified arrows before navigation sees them`() {
    assertEquals(
      UiBuilderEditorEvent.MoveSelected(EditorMoveDirection.Before),
      resolve(EditorChord(Key.DirectionUp, command = true, shift = false))?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Previous),
      resolve(EditorChord(Key.DirectionUp, command = false, shift = false))?.event,
    )
  }

  @Test
  fun `a chord that does not care about shift answers either way`() {
    // Holding shift while arrowing still navigates. `shift = null` is not `shift = false`, and the
    // difference is what lets Ctrl+Z stay undo while Ctrl+Shift+Z is redo.
    assertEquals(
      UiBuilderEditorEvent.SelectRelative(EditorSelectionMove.Next),
      resolve(EditorChord(Key.DirectionDown, command = false, shift = true))?.event,
    )
  }

  @Test
  fun `typing is not a shortcut`() {
    // The command modifier is what separates an editor action from someone typing into a property.
    assertNull(resolve(EditorChord(Key.C, command = false, shift = false)))
    assertNull(resolve(EditorChord(Key.V, command = false, shift = false)))
    assertNull(resolve(EditorChord(Key.Z, command = false, shift = false)))
  }

  @Test
  fun `the panel names each chord once and describes all of them`() {
    val chords = EDITOR_SHORTCUTS.map { it.chord }
    assertEquals(chords.distinct(), chords, "two rows would advertise the same chord")
    assertTrue(EDITOR_SHORTCUTS.all { it.chord.isNotBlank() && it.description.isNotBlank() })
    assertTrue(EDITOR_GESTURES.all { it.first.isNotBlank() && it.second.isNotBlank() })
  }

  @Test
  fun `a closed canvas leaves only the chords that open a pane`() {
    // With the selection overlay gone there is nothing on screen to show what a Delete or an arrow
    // just did, so those chords would edit invisibly and surprise later.
    assertEquals(
      UiBuilderEditorEvent.TogglePane(EditorPane.Editor),
      editorShortcutFor(EditorChord(Key.Enter, command = true, shift = true), editing = false)
        ?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.TogglePane(EditorPane.Preview),
      editorShortcutFor(EditorChord(Key.Enter, command = true, shift = false), editing = false)
        ?.event,
    )
    listOf(Key.Delete, Key.Z, Key.C, Key.V, Key.D, Key.DirectionDown, Key.P).forEach { key ->
      assertNull(
        editorShortcutFor(
          EditorChord(
            key,
            command = key != Key.Delete && key != Key.DirectionDown,
            shift = false,
          ),
          editing = false,
        ),
        "$key should be inert with the authoring canvas closed",
      )
    }
    // And every one of them is live again the moment the canvas comes back.
    assertNotNull(editorShortcutFor(EditorChord(Key.Delete, command = false, shift = false)))
    // Ctrl/⌘+P is the browser's print dialog and is deliberately not ours, pane or no pane.
    assertNull(editorShortcutFor(EditorChord(Key.P, command = true, shift = false)))
  }

  @Test
  fun `the shifted Enter reaches the editor pane rather than the preview one`() {
    assertEquals(
      UiBuilderEditorEvent.TogglePane(EditorPane.Editor),
      resolve(EditorChord(Key.Enter, command = true, shift = true))?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.TogglePane(EditorPane.Preview),
      resolve(EditorChord(Key.Enter, command = true, shift = false))?.event,
    )
  }

  @Test
  fun `both delete keys reach the same action`() {
    assertEquals(
      UiBuilderEditorEvent.DeleteSelected,
      resolve(EditorChord(Key.Delete, command = false, shift = false))?.event,
    )
    assertEquals(
      UiBuilderEditorEvent.DeleteSelected,
      resolve(EditorChord(Key.Backspace, command = false, shift = false))?.event,
    )
  }

  @Test
  fun `the zoom chords every canvas tool uses reach the canvas zoom`() {
    fun zoom(key: Key, shift: Boolean = false) =
      resolve(EditorChord(key, command = true, shift = shift))?.view
    val zoomIn = EditorViewCommand.Zoom(CanvasZoomCommand.In)
    assertEquals(zoomIn, zoom(Key.Equals))
    // Ctrl/Cmd++ is a shifted `=` on most layouts, and a keypad + is a plus with no shift at all.
    assertEquals(zoomIn, zoom(Key.Equals, shift = true))
    assertEquals(zoomIn, zoom(Key.NumPadAdd))
    assertEquals(EditorViewCommand.Zoom(CanvasZoomCommand.Out), zoom(Key.Minus))
    assertEquals(EditorViewCommand.Zoom(CanvasZoomCommand.Out), zoom(Key.NumPadSubtract))
    assertEquals(EditorViewCommand.Zoom(CanvasZoomCommand.Fit), zoom(Key.Zero))
    assertEquals(EditorViewCommand.Zoom(CanvasZoomCommand.ActualSize), zoom(Key.One))
  }

  @Test
  fun `a bare digit or minus is typing, not a zoom`() {
    // Without the command modifier these are characters, and leaving them unclaimed is also what
    // leaves them to a focused control that wants them.
    listOf(Key.Equals, Key.Minus, Key.Zero, Key.One).forEach { key ->
      assertNull(resolve(EditorChord(key, command = false, shift = false)), "$key")
    }
  }

  @Test
  fun `question mark opens the shortcuts and a plain slash does not`() {
    assertEquals(
      EditorViewCommand.ShowShortcuts,
      resolve(EditorChord(Key.Slash, command = false, shift = true))?.view,
    )
    assertNull(resolve(EditorChord(Key.Slash, command = false, shift = false)))
  }

  @Test
  fun `a closed canvas leaves the browser its own zoom but still offers help`() {
    // With nothing to zoom, the chord is not ours: unclaimed, it reaches the page zoom it always
    // did.
    listOf(Key.Equals, Key.Minus, Key.Zero, Key.One).forEach { key ->
      assertNull(
        editorShortcutFor(EditorChord(key, command = true, shift = false), editing = false),
        "$key should be the browser's with the authoring canvas closed",
      )
    }
    assertEquals(
      EditorViewCommand.ShowShortcuts,
      editorShortcutFor(EditorChord(Key.Slash, command = false, shift = true), editing = false)
        ?.view,
    )
  }

  @Test
  fun `every shortcut does exactly one thing`() {
    assertTrue(EDITOR_SHORTCUTS.all { (it.event == null) != (it.view == null) })
  }

  @Test
  fun `zoom is not taken while something has replaced the canvas`() {
    // The editor pane is open but the canvas is not drawn — a revision or suggestion under review,
    // or a compact layout's preview tab. The browser keeps its page zoom; the rest stay live.
    listOf(Key.Equals, Key.Minus, Key.Zero, Key.One).forEach { key ->
      assertNull(
        editorShortcutFor(
          EditorChord(key, command = true, shift = false),
          editing = true,
          canvasShown = false,
        ),
        "$key should be the browser's with no canvas on screen",
      )
    }
    assertEquals(
      UiBuilderEditorEvent.Undo,
      editorShortcutFor(
          EditorChord(Key.Z, command = true, shift = false),
          editing = true,
          canvasShown = false,
        )
        ?.event,
    )
    assertEquals(
      EditorViewCommand.Zoom(CanvasZoomCommand.In),
      editorShortcutFor(
          EditorChord(Key.Equals, command = true, shift = false),
          editing = true,
          canvasShown = true,
        )
        ?.view,
    )
  }
}
