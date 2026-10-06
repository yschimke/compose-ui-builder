# Design tokens: the values a design system lets a design re-skin

A design system has values a design is allowed to change without leaving it: the primary colour,
the colour of a button, the spacing of a list, the size of an icon. A **design token** names one
of them, says where it lands, and has a **nullable default**. Null is the usual default, and it
means "the design system's own value", which is what an unset property already draws.

The catalog declares tokens, because they are facts about the design system. The design holds
what they are set to, as ordinary properties.

## Declared by the catalog

Tokens live in `statusSemantics.designTokens`. `CatalogCapabilityV1` is published from
compose-preview-contracts and cannot grow a field from here, and `statusSemantics` is the
catalog's own open vocabulary. `previewSurfaces`, `componentMenu` and `frame` live there for the
same reason.

```json
"designTokens": {
  "tokens": [
    { "id": "color.primary", "label": "Primary", "kind": "color", "default": null,
      "theme": [{ "component": "wear-m3/screen-scaffold", "property": "themePrimaryColor" }] },
    { "id": "space.list", "label": "List spacing", "kind": "number", "default": null,
      "minimum": 0, "maximum": 24,
      "components": [
        { "component": "wear-m3/transforming-lazy-column", "property": "verticalSpacingDp" },
        { "component": "layout/column", "property": "verticalSpacingDp" }
      ] }
  ]
}
```

| Field | Meaning |
| --- | --- |
| `id` | Stable and dotted (`color.primary`). The first declaration of an id wins. |
| `label`, `notes` | What the Theme panel shows. |
| `kind` | `color` (a `#RRGGBB` literal or a theme role) or `number`. A number needs `minimum` < `maximum`, and may say `integer`. |
| `default` | The catalog's value, or `null` for the design system's own. A reset writes it, or unsets the bound properties when it is null. |
| `theme` | Bindings applied only to the design's **theme host**: the top-level node of that component, one level under a board, as `themeHost` reads it. |
| `components` | Bindings applied to **every** node of that component. |

The reader (`DesignTokens.from`) is lenient in the way the rest of `statusSemantics` is read. An
entry with no id, an unknown kind, no binding or a backwards range is skipped rather than failing
the catalog. A catalog that says nothing has no tokens, and the Theme panel shows no token
section.

### What `wear-m3` declares

`wearDesignTokens()` in `:ui-builder-runtime` declares the tokens below. It is recorded in the
`wear-m3-capabilities-v1.json` golden. Every default is null, because every bound property already
says *unset keeps Wear's own*.

| Group | Tokens | Lands on |
| --- | --- | --- |
| Theme | primary, on primary, primary container, secondary, tertiary, surface container, on surface, background, error | the `ScreenScaffold`'s `theme…Color`, where a Wear design hangs its `MaterialTheme` colour scheme |
| Components | list spacing (0–24) | every `transforming-lazy-column` and `layout/column`'s `verticalSpacingDp` |
| | row spacing (0–24) | every `layout/row`'s `horizontalSpacingDp` |
| | icon size (12–48) | every `wear-m3/icon`'s `sizeDp` |
| | button container, button content | `containerColor` / `contentColor` on every button, icon button and edge button |
| | card container | every `wear-m3/card`'s `containerColor` |

`m3-catalog` declares none yet. Its theme is the `m3/surface` Theme builder below the token
section, and adding tokens there is a declaration in its catalog, not a change to the editor.

## Applied by writing properties

A token is not stored a second time. **What it is set to is read back from the document:**

- **Unset**: no bound property holds a value, so the design system's own value is drawn;
- **Set**: every bound property that holds a value holds the same one;
- **Mixed**: they disagree. This happens when the token was applied and then one property was
  edited by hand.

That keeps the token honest. It cannot claim a value the canvas is not drawing. It also needs no
new document field, which compose-preview-contracts could not carry. A token's value is shared,
saved, undone and exported exactly as the properties it wrote are, because it *is* those
properties.

**Apply** writes the value into every bound property as one command. Every write is a property
write, so one undo takes it back. A colour is written as `color` for a literal and `colorToken`
for a role, as the inspector writes them. A number keeps an `int` property an `int`. The value
goes through the same validators a typed value does, so a role the canvas does not draw (for
example `primaryContainer`) is refused at the door. A value outside the token's range or kind is
refused by name, and so is a token with nowhere to land ("this design has no icon").

**Reset** writes the catalog's default. When the default is null, Reset unsets every bound
property instead, except a required one, which cannot be unset.

Two kinds of property are never a token's to replace: one that holds a state binding, and one
that holds a component argument. Both are skipped.

## Tokens and tunables

A number token can be tuned. **Tune** on its row puts a slider in the Tune card
([`UI_BUILDER_TUNABLES.md`](UI_BUILDER_TUNABLES.md)) whose range is the token's and whose targets
are every property the token binds. Dragging redraws the canvas and the preview panes across all
of them without writing anything. **Apply** in the Tune card writes the values, which is applying
the token.

A token tunable's targets are **the token's**, re-read after every change to the design. A list
added after the slider was made is tuned with the rest, because a token is a statement about
every node of a component, not about the nodes that existed when somebody pressed Tune. For the
same reason its targets cannot be unlinked or re-linked by hand. An unset token with no default
starts its slider mid-range, since "the design system's own value" is not a number to start from.

## Where it lives

| Piece | File |
| --- | --- |
| Catalog vocabulary and reader | `ui-builder/.../capability/DesignTokens.kt`, `CapabilityCatalog.designTokens` |
| Targets, reading, writes, token tunables | `ui-builder/.../editor/UiBuilderDesignTokens.kt` |
| Theme panel section | `ui-builder/.../editor/UiBuilderDesignTokensPanel.kt` |
| Events (`ApplyDesignToken`, `TuneDesignToken`) and reducer | `UiBuilderEditorEvents.kt`, `UiBuilderEditorState.kt` |
| `wear-m3`'s declaration | `ui-builder-runtime/.../service/WearM3Catalog.kt` (`wearDesignTokens`) |
| Tests | `ui-builder/src/jvmTest/.../DesignTokensTest.kt` |
