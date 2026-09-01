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
