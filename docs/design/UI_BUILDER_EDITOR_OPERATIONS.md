# UI builder editor operation parity

The interactive Wasm editor exposes four local-session controls backed only by the collaboration
reducer's existing command types:

- **Duplicate** emits one `DesignCommand` batch containing ordered `InsertNode` operations for the
  selected node and its complete subtree. IDs are deterministic within the session, authored slot
  order is retained, and the catalog document validator accepts or rejects the whole batch.
- **Delete** emits one `DesignCommand` containing `DeleteNode`. The editor disables it for the sole
  root and when removing a child would violate the owning slot's minimum cardinality.
- **Undo** emits `UndoCommand` for the newest active `wasm-editor` operation. Commands authored by
  another actor are never selected.
- **Redo** emits `RedoCommand` for the newest un-redone `wasm-editor` undo record.

The toolbar and keyboard share the same editor events. Shortcuts are `Ctrl/Command+D` for
duplicate, `Delete` or `Backspace` for delete, `Ctrl/Command+Z` for undo, and
`Ctrl/Command+Shift+Z` or `Ctrl+Y` for redo. Global editor shortcuts are suspended while either
text input has focus so ordinary text editing is not converted into a design operation.

## Visual evidence

The screenshots are deterministic 1440×900 Playwright captures. The before image is the committed
interactive editor on `origin/main`; the after image follows insert, property edit, reorder,
duplicate, delete, undo, and redo operations and shows the new toolbar controls.

| Before | After |
| --- | --- |
| ![Editor before operation controls](../../preview-harness/snapshots/ui-builder-editor-history-before.png) | ![Editor with operation controls](../../preview-harness/snapshots/ui-builder-interactive-editor.png) |

The same harness separately crops the pinned design canvas and compares it with the clean 1280×800
Jetcaster render using exact geometry assertions and the existing sub-0.2% one-channel raster
tolerance. Editor chrome and history controls therefore cannot change the design's layout mode or
canvas geometry unnoticed.

## Starter content

A container inserted from the palette arrives holding typical content rather than empty: an icon
button holds an icon, a button reads `Button`, a card carries a title over supporting text, a lazy
column holds three items, a search field has a placeholder and a magnifier. The seeded nodes are
ordinary nodes — selectable, editable, deletable — so the cost of a guess nobody wanted is one
keystroke, against retyping the same subtree on every insert.

Two kinds of child come out of one insert and the difference is worth keeping straight:

- a **required-slot fill** is what keeps the document valid. A slot declaring a minimum has to have
  children or the catalog validator rejects the batch, so one is chosen from the slot's accepted
  traits. This predates starter content and still runs.
- **starter content** is what makes the insert look designed. It is declared per component and slot
  in [`StarterContent.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/StarterContent.kt),
  checked against the catalog at the point of use, and **dropped rather than enforced** when it does
  not check out — a stale table entry degrades an insert to the old behaviour and can never make a
  component uninsertable. `StarterContentTest` asserts the same rules, so a degradation that would
  be silent in the product fails a build instead.

Three rules the table obeys:

- **Pure layout primitives are not seeded.** `layout/box`, `layout/row`, `layout/column` and
  `m3/surface` exist to hold whatever is put in them and have no typical content to be right about.
  The line is whether a component's *shape* is recognisable without its content.
- **A seeded node authors its own slots or inherits the expansion.** A slot the table names is
  authored exactly and the child does not then pick up its own starter content; a slot left unnamed
  expands the ordinary way. That is why `m3/search-bar` needs no entry at all — the search field its
  required `inputField` resolves to brings the placeholder and magnifier with it — and why an item
  card in a lazy column holds one line rather than the card's own two.
- **Wrapping a selection seeds nothing.** The wrapped nodes are the content; a seed there would be
  inserted and deleted inside one batch.

One insert is one atomic `DesignCommand`, seeded subtree included, so undo removes the whole thing
and a rejected insert leaves no partial nodes. The largest seed in the table is well inside
`maximumOperationsPerBatch`, and a test holds that bound.

### Visual evidence

`StarterContentInsertPreview` renders six palette inserts side by side, from a document the reducer
builds rather than one anybody authored. Before and after, with what each frame shows, are in
[`renders/ui-builder-starter-content/`](../../renders/ui-builder-starter-content/README.md).

| Before | After |
| --- | --- |
| ![Six inserts, generic or empty](../../renders/ui-builder-starter-content/insert.before.png) | ![The same six inserts, seeded](../../renders/ui-builder-starter-content/insert.after.png) |

## Property and Google icon editing

The property inspector is driven by the selected component's capability schema. It exposes text,
bounded numbers, booleans, enums, and colors, including optional properties that are not yet
authored on the node. Icon nodes additionally use a searchable Google Material Icons picker. Icon
keys share one allowlist across capability validation, the native renderer, structured SVG
recording, and generated Compose export.

| Before | Catalog | After |
| --- | --- | --- |
| ![Search icon before editing](../../preview-harness/snapshots/ui-builder-google-icon-before.png) | ![Searchable Google Material Icons catalog](../../preview-harness/snapshots/ui-builder-google-icon-picker.png) | ![Home icon after editing](../../preview-harness/snapshots/ui-builder-google-icon-selected.png) |

## Mobile workspace

Below `840dp`, the editor defaults to a design-only workspace with a compact action bar. Components
and properties are available from persistent bottom-docked tabs; selecting the active tab collapses
its panel. The component panel retains catalog search, layers, selection, reordering, and drag/drop,
while the property panel retains component and screen-environment editing. Duplicate, delete,
reconnect, and help remain available from the compact **More** menu.

The mobile harness runs at 390×844 CSS pixels and verifies that neither panel is composed initially,
both panels can replace one another without navigation, and the pinned 1280×800 design remains
scaled inside the available width.

| Before | Design first | Components | Properties |
| --- | --- | --- | --- |
| ![Desktop workspace clipped at mobile width](../../preview-harness/snapshots/ui-builder-mobile-before.png) | ![Mobile design-first workspace](../../preview-harness/snapshots/ui-builder-mobile-design.png) | ![Mobile component dock](../../preview-harness/snapshots/ui-builder-mobile-components.png) | ![Mobile property dock](../../preview-harness/snapshots/ui-builder-mobile-properties.png) |
