package ee.schimke.composeai.uibuilder

import ee.schimke.composeai.uibuilder.editor.*
import kotlin.test.*

class ModifierMenuTest {
  private fun toggles(count: Int) =
    (1..count).map { EditorModifierToggle("future$it", "Future $it", it % 2 == 0) }

  private fun leaves(actions: List<UiBuilderMenuEntry.Action>): List<UiBuilderMenuEntry.Action> =
    actions.flatMap {
      if (it.children.isEmpty()) listOf(it) else leaves(it.children)
    }

  @Test
  fun `inline and submenu thresholds include their upper boundary`() {
    for (count in listOf(0, 1, 4, 5, 12, 13, 24, 25, 100)) {
      val offered = toggles(count)
      val entries = modifierMenuEntries(offered, {}, {})
      assertEquals(offered.map { it.label }, leaves(entries).map { it.label })
      when {
        count <= 4 -> assertTrue(entries.all { it.children.isEmpty() })
        count <= 12 -> {
          assertEquals("Modifiers", entries.single().label)
          assertEquals(count, entries.single().children.size)
        }
        else -> {
          assertTrue(entries.all { it.label.startsWith("Behavior") })
          assertTrue(entries.all { it.children.size in 1..12 })
          assertEquals(entries.size, entries.map { it.label }.distinct().size)
        }
      }
    }
  }

  @Test
  fun `real registry groups preserve states and dispatch the exact key after dismissing`() {
    val offered = MENU_MODIFIERS.mapIndexed { i, modifier ->
      EditorModifierToggle(modifier.key, modifier.label, i % 2 == 0)
    }
    val calls = mutableListOf<String>()
    val entries = modifierMenuEntries(offered, { calls += "dismiss" }, { calls += it })
    assertTrue(entries.size > 1)
    assertTrue(entries.all { it.children.size in 1..12 })
    val all = leaves(entries)
    assertEquals(offered.size, all.size)
    offered.forEach { toggle ->
      val action = all.single { it.label == toggle.label }
      assertEquals(toggle.applied, action.selected)
      assertEquals(
        if (toggle.applied) "Remove ${toggle.label}" else "Apply ${toggle.label}",
        action.contentDescription,
      )
      action.onClick()
      assertEquals(listOf("dismiss", toggle.type), calls)
      calls.clear()
    }
    fun groupOf(label: String) =
      entries.single { group -> group.children.any { it.label == label } }.label
    assertTrue(groupOf("Fill the width").startsWith("Layout & sizing"))
    assertEquals("Appearance", groupOf("Remote: graphicsLayer"))
    assertEquals("Scrolling", groupOf("Remote: basicMarquee"))
    assertEquals("Animation", groupOf("Animate between states"))
    assertEquals("Behavior", groupOf("Remote: semantics"))
  }

  @Test
  fun `selection actions stay at the root with shortcuts availability and dispatch intact`() {
    val events = mutableListOf<UiBuilderEditorEvent>()
    val calls = mutableListOf<String>()
    fun entries(toggles: List<EditorModifierToggle>) =
      editorSelectionMenuEntries(
          modifierToggles = toggles,
          onToggleModifier = { calls += it },
          canDuplicate = true,
          canCopy = true,
          canCut = false,
          canPaste = false,
          canDelete = true,
          wrapCandidates = emptyList(),
          canUnwrap = true,
          onOpenProperties = { calls += "properties" },
          onQuickEdit = { calls += "quick" },
          onCopyLink = {},
          onCopyCode = {},
          onDismiss = { calls += "dismiss" },
          dispatch = { events += it },
        )
        .filterIsInstance<UiBuilderMenuEntry.Action>()
    val compact = entries(emptyList())
    val large = entries(toggles(25))
    compact.forEach { original ->
      val action = large.single { it.label == original.label }
      assertEquals(original.enabled, action.enabled)
      assertEquals(original.shortcut, action.shortcut)
      assertEquals(original.contentDescription, action.contentDescription)
    }
    large.single { it.label == "Duplicate" }.onClick()
    assertEquals(listOf<UiBuilderEditorEvent>(UiBuilderEditorEvent.DuplicateSelected), events)
    assertEquals(listOf("dismiss"), calls)
    calls.clear()
    large.single { it.label == "Properties" }.onClick()
    assertEquals(listOf("dismiss", "properties"), calls)
    assertFalse(large.single { it.label == "Paste" }.enabled)
    assertEquals("E", large.single { it.label == "Quick edit" }.shortcut)
    // Group only the current selection's offered subset, never add missing registry actions.
    val subset = MENU_MODIFIERS.take(3).map { EditorModifierToggle(it.key, it.label, false) }
    assertEquals(
      subset.map { it.label },
      leaves(modifierMenuEntries(subset, {}, {})).map { it.label },
    )
  }
}
