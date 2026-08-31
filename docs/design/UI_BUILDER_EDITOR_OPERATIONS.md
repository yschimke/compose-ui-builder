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
