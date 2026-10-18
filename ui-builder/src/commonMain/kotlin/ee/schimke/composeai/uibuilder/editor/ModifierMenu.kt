package ee.schimke.composeai.uibuilder.editor

/** Limits count the available actions, after selection/catalog filtering, never the registry. */
internal const val INLINE_MODIFIER_LIMIT = 4
internal const val MODIFIER_SUBMENU_LIMIT = 12

private enum class ModifierMenuGroup(val label: String) {
  Layout("Layout & sizing"),
  Appearance("Appearance"),
  Scrolling("Scrolling"),
  Animation("Animation"),
  Behavior("Behavior"),
}

/**
 * Use stable registry keys, not translated labels. New/unknown calls remain reachable in Behavior.
 */
private fun modifierMenuGroup(key: String): ModifierMenuGroup =
  when (key.removePrefix(REMOTE_CALL_KEY_PREFIX)) {
    "fillMaxSize",
    "fillMaxWidth",
    "fillMaxHeight",
    "matchParentSize",
    "padding",
    "align",
    "alignHorizontal",
    "alignVertical",
    "weight",
    "collapsiblePriority",
    "defaultMinSize",
    "wrapContentHeight",
    "wrapContentWidth",
    "wrapContentSize",
    "size",
    "width",
    "height",
    "widthIn",
    "heightIn",
    "offset" -> ModifierMenuGroup.Layout
    "background",
    "border",
    "clip",
    "graphicsLayer",
    "alpha",
    "rotate",
    "scale",
    "zIndex" -> ModifierMenuGroup.Appearance
    "verticalScroll",
    "horizontalScroll",
    "basicMarquee" -> ModifierMenuGroup.Scrolling
    "sharedElement",
    "animateEnterExit" -> ModifierMenuGroup.Animation
    else -> ModifierMenuGroup.Behavior
  }

internal fun modifierMenuEntries(
  toggles: List<EditorModifierToggle>,
  onDismiss: () -> Unit,
  onToggle: (String) -> Unit,
): List<UiBuilderMenuEntry.Action> {
  fun action(toggle: EditorModifierToggle) =
    UiBuilderMenuEntry.Action(
      label = toggle.label,
      selected = toggle.applied,
      reserveIconSpace = true,
      contentDescription =
        if (toggle.applied) "Remove ${toggle.label}" else "Apply ${toggle.label}",
      onClick = {
        onDismiss()
        onToggle(toggle.type)
      },
    )
  fun submenu(label: String, children: List<UiBuilderMenuEntry.Action>) =
    UiBuilderMenuEntry.Action(label, reserveIconSpace = true, children = children, onClick = {})

  if (toggles.size <= INLINE_MODIFIER_LIMIT) return toggles.map(::action)
  if (toggles.size <= MODIFIER_SUBMENU_LIMIT) {
    return listOf(submenu("Modifiers", toggles.map(::action)))
  }
  return ModifierMenuGroup.entries.flatMap { group ->
    // Preserve registry order within each purpose. Split even an oversized future category so
    // it cannot silently recreate the original giant menu. Numbered ranges keep even long registry
    // labels out of submenu titles.
    val chunks =
      toggles.filter { modifierMenuGroup(it.type) == group }.chunked(MODIFIER_SUBMENU_LIMIT)
    chunks.mapIndexed { index, chunk ->
      val label =
        if (chunks.size == 1) group.label
        else
          "${group.label} (${index * MODIFIER_SUBMENU_LIMIT + 1}–${index * MODIFIER_SUBMENU_LIMIT + chunk.size})"
      submenu(label, chunk.map(::action))
    }
  }
}
