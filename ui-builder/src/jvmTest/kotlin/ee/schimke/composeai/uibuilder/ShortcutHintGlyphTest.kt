package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.COMMAND_MODIFIER
import ee.schimke.composeai.uibuilder.editor.EDITOR_GESTURES
import ee.schimke.composeai.uibuilder.editor.EDITOR_SHORTCUTS
import ee.schimke.composeai.uibuilder.editor.UiBuilderMenuEntry
import ee.schimke.composeai.uibuilder.editor.editorSelectionMenuEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every shortcut hint the editor draws is spelled in characters its own fonts carry.
 *
 * A glyph none of them has — `⌘` was the one — makes the Wasm build fetch a Noto symbols font from
 * `fonts.gstatic.com`, which an MCP App host's CSP refuses, so the hint drew as a box. The command
 * modifier is therefore written as letters ([COMMAND_MODIFIER]); this pins that nobody puts a
 * symbol back.
 */
class ShortcutHintGlyphTest {

  /**
   * ASCII, plus the four plain arrows the navigation chords use (`←↑→↓`, in the WGL4 set every
   * Roboto build carries). Anything else — a Mac modifier symbol above all — is a font fetch.
   */
  private fun drawable(char: Char): Boolean = char.code in 0x20..0x7e || char in "←↑→↓"

  private fun assertDrawable(where: String, hints: List<String>) {
    val offending = hints.filter { hint -> !hint.all(::drawable) }
    assertEquals(emptyList(), offending, "$where: hints with a glyph outside the editor's fonts")
  }

  @Test
  fun `the command modifier is spelled in letters`() {
    assertEquals("Ctrl/Cmd", COMMAND_MODIFIER)
  }

  @Test
  fun `the shortcuts panel spells every chord in drawable characters`() {
    assertDrawable("shortcuts", EDITOR_SHORTCUTS.map { it.chord })
    assertDrawable("gestures", EDITOR_GESTURES.map { it.first })
    assertTrue(EDITOR_SHORTCUTS.any { it.chord == "Ctrl/Cmd+Z" }, "undo is advertised as text")
  }

  @Test
  fun `the node menu's shortcut hints are drawable`() {
    val entries =
      editorSelectionMenuEntries(
        modifierToggles = emptyList(),
        onToggleModifier = {},
        canDuplicate = true,
        canCopy = true,
        canCut = true,
        canPaste = true,
        canDelete = true,
        wrapCandidates = emptyList(),
        canUnwrap = false,
        onOpenProperties = {},
        onQuickEdit = {},
        onDismiss = {},
        dispatch = {},
      )
    val hints =
      entries
        .filterIsInstance<UiBuilderMenuEntry.Action>()
        .flatMap { listOf(it) + it.children }
        .mapNotNull { it.shortcut }
    assertTrue("Ctrl/Cmd+D" in hints, "Duplicate's hint: $hints")
    assertDrawable("node menu", hints)
  }
}
