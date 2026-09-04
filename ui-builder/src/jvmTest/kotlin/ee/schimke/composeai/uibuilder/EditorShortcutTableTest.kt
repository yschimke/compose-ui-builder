package ee.schimke.composeai.uibuilder

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shortcut table is both the handler's rules and the panel's contents, so the thing worth
 * testing is that those cannot disagree.
 */
class EditorShortcutTableTest {
  private fun resolve(chord: EditorChord) = EDITOR_SHORTCUTS.firstOrNull { it.matches(chord) }

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
}
