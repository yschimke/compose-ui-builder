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

## What the catalog advertises

The capability catalog is the palette. A component the catalog does not declare cannot be inserted,
has no inspector, and is `UNKNOWN_COMPONENT` to the validator wherever it appears — however complete
its renderer and exporter support happens to be.

Five components were in exactly that state: `m3/center-aligned-top-app-bar`, `m3/list-item`,
`m3/primary-tab-row`, `m3/tab` and `shape/colour-dot`, each with a renderer branch, a Compose emitter
and an entry in the exporter's field table, and none of them declared anywhere. The checked-in
Confetti design pins `m3-catalog` and uses all five, so a whole shipped screen was a document its own
catalog could say nothing about.

Declaring them turned up the thing that always hides behind an unadvertised component: **properties
the canvas drew and the export discarded**. A top app bar's `containerColor` and
`scrolledContainerColor`, a list item's `startAccentColor`, and a tab row's `selectedIndex` — which
the exporter hard-coded to zero — were all read by the renderer and dropped on the way to Kotlin.
Nobody could insert the components, so nobody found out. All four now reach the generated source, and
`scrollBehavior` joins them: the canvas cannot draw a scroll behavior, because one is driven by a
nested-scroll connection a document has no scaffold to carry, so the catalog says so on the property
and the generated screen gets the real thing.

### Visual evidence

`CatalogUnadvertisedComponentsPreview` inserts four of the five into a column, with no editing after
the drop. What each is, and why there is no before image, are in
[`renders/ui-builder-advertised-components/`](../../renders/ui-builder-advertised-components/README.md).

![A top app bar, a tab row, a list item and a colour dot](../../renders/ui-builder-advertised-components/advertised.after.png)

## Text input

`m3/text-field` and `m3/radio-button`. The text field is the first component whose value the
operator *types*, which raises the question the rest of the catalog does not: where does that text
go?

It goes to a **declared state variable**, the way a search input's does. The canvas reads it through
the live state and the export writes back to it — `value = searchQuery`,
`onValueChange = { searchQuery = it }` — so typing in the preview changes the design's state rather
than a field's private memory. A field bound to nothing emits an empty handler and a literal, never
a local `remember`: a generated screen whose field kept its own text would look like it worked and
would not be the screen anybody designed.

One id carries both Material composables. `TextField` and `OutlinedTextField` take the same
arguments and differ in nothing a design authors, so `variant` picks between them — the choice
`m3/card` already makes for its three. The component record leaves the id uncovered for exactly that
reason: a record names one callable.

### Visual evidence

Both variants and both radio states in [`renders/ui-builder-text-input/`](../../renders/ui-builder-text-input/README.md).

![A filled text field, an outlined text field, a selected radio button and an unselected one](../../renders/ui-builder-text-input/text-input.after.png)

## Selection controls

`m3/checkbox` and `m3/switch` are one pair rather than two changes: the same `checked`/`enabled`
properties, the same `onCheckedChange`, the same binding to a declared state variable, and the same
risk — that the renderer and the exporter read that binding differently.

Both bind `checked` to a flag through `stateEquals`, exactly as `m3/filter-chip`'s `selected` does,
and both take a `click` action, so a checkbox wired to a state variable ticks on the canvas *and*
generates `onCheckedChange = { notify = !notify }`. That is the difference between a control and a
picture of one, and it is asserted on one document in `SelectionControlTest` so the two projections
cannot drift apart.

They are also the first pair whose **component record** is authored rather than deferred: Material
declares `onCheckedChange` as `((Boolean) -> Unit)?`, so a call site can write `null` for it, which
is the case `ComponentSnippets` documents as the reason `Checkbox`, `RadioButton` and `Switch` get
through. The record is exercised end to end in `M3CatalogComponentRecordTest` rather than trusted —
a record nothing generates against is a table nobody has checked.

### Visual evidence

Both, on and off, in [`renders/ui-builder-selection-controls/`](../../renders/ui-builder-selection-controls/README.md).

![A checked checkbox, an unchecked checkbox, a switch on and a switch off](../../renders/ui-builder-selection-controls/selection-controls.after.png)

## The dialog and the pickers

Three components the catalog did not carry: `m3/dialog`, `m3/date-picker` and `m3/time-picker`.
Each raises a question the rest of the catalog does not, and the answers are here because they are
decisions rather than implementation detail.

### A dialog is drawn inline, and exported that way too

The canvas draws `m3/dialog` where it sits, with `AlertDialog`'s own surface, spacing and button row
— a 28dp corner, `surfaceContainerHigh`, 6dp tonal elevation, 24dp padding, 280..560dp wide, and the
dismissing action before the confirming one. It is not a real `Dialog`, for two reasons:

- a `Dialog` is a **window**. It leaves the layout, centres itself over the screen and scrims what is
  behind it, so it would draw outside the canvas being arranged, could not be hit-tested as a node,
  and would export as a picture of a scrim; and
- `AlertDialog` requires `onDismissRequest`, and **a design has nothing to write into it**. The
  document's actions are `toggle`, `set`, `select` and `selectOrClear` over declared state variables;
  there is no "close this dialog", because a dialog is not bound to a visibility state. The real API
  would generate a modal nobody can dismiss.

So the Compose export emits `BuilderDialogSurface`, a compatibility helper that matches the canvas,
under the same banner every other helper carries — an export diagnostic, not a claim of API parity.
The component record leaves the id uncovered for the same reason, in its own words.

Its slots are `AlertDialog`'s parameters by name — `icon`, `title`, `text`, `dismissButton`,
`confirmButton` — and `confirmButton` is the only one with a minimum, because a dialog with no way
to say yes is not a dialog. [Starter content](#starter-content) fills all four, so an inserted dialog
reads "Dialog title", explains itself, and offers Cancel beside OK.

### A picker must not read the clock

`DatePicker` opens on the current month and rings today's cell; `rememberTimePickerState` starts at
the current time. Left alone, both make a design whose render changes overnight — which breaks the
committed renders, the visual diff and the SVG lane at once, and breaks them silently.

So the state is pinned by the document. `m3/date-picker` carries `selectedDate` as an ISO
`YYYY-MM-DD` — used for the selection **and** for the month the calendar opens on — and
`m3/time-picker` carries `hour` and `minute`. An insert arrives on a fixed day and time rather than
on today, the Compose export writes the same values as literals, and the picker preview below is the
check with a picture attached.

`input` is not a second component in either case: it is Material's `DisplayMode.Input` and its
`TimeInput`, reached through a `mode` property, which is why two ids cover four faces.

### Visual evidence

`CatalogDialogAndPickersPreview` renders all five from a document the reducer builds. What each cell
is, and why there is no before image, are in
[`renders/ui-builder-dialog-pickers/`](../../renders/ui-builder-dialog-pickers/README.md).

![A dialog, a calendar, a date input, a 24-hour dial and a 12-hour time input](../../renders/ui-builder-dialog-pickers/dialog-and-pickers.after.png)

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
