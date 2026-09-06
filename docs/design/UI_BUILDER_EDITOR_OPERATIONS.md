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

## What a slot accepts

A slot declares `acceptedRoles` and `acceptedTraits`, and a child has to pass **both**: an empty
list constrains nothing on its axis, and `AnyContent` in the traits is the same as declaring none.
The rule lives once in `SlotCapability.accepts`
([`CapabilityCatalog.kt`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/capability/CapabilityCatalog.kt)),
which the validator, the drop targets and the required-slot fill all call, and once more as
`slotAccepts` in `:ui-builder-runtime`, which cannot reach that module. The two are pinned to the
same committed table,
[`slot-acceptance-v1.json`](fixtures/ui-builder/slot-acceptance-v1.json) — for every slot of every
enabled catalog, the component ids it accepts — by `CatalogGrammarTest` on the editor side and
`SlotAcceptanceTest` on the server side, so a change to either copy, or to the catalog, shows up
as a diff in that file rather than as a document one side accepts and the other refuses.

It used to be *either*. Every m3 slot lists the coarse roles beside its traits, so the role decided
everything: `layout/scaffold.topBar` declares `Container` + `TopBar`, a lazy grid is a `Container`,
and a design shipped with a lazy grid in its top bar holding text and icon buttons the way
`GridItem` was meant to forbid. Twenty-three of the thirty-nine components fit that slot under the
old rule. The server's copy was looser still — an empty role list counted as a *match* — so for the
catalogs built in code, where no slot declares roles, the server checked traits on no slot at all.

Requiring both would have refused the checked-in designs at twenty places, and the blank template
with them: a box in `scaffold.content` is not `ScreenContent`, a text in `lazy-column.items` is not
a `ListItem`. Those traits described what a slot is *for*, not what Compose takes, so the catalog
now tells the two kinds of slot apart:

- **Free content lambdas** — `scaffold.content`, both panes, every `lazy-*.items`, `button.content`,
  `filter-chip.leadingIcon`, `snackbar-host.snackbar`, `search-bar.expandedContent` — declare
  `AnyContent`, because the Compose parameter is a lambda that takes any composable.
- **Structural slots** — `scaffold.topBar`, `dialog.confirmButton`, `tab-row.tabs`,
  `search-bar.inputField`, `icon-button.content`, the widget background — keep their trait, because
  the parameter has a shape the trait names. The top bar takes a `TopBar` or a `LayoutPrimitive`,
  a trait `layout/box`, `layout/row` and `layout/column` now carry: the Jetcaster top bar is a
  `Row`, and a custom app bar is a legitimate thing to build, where a scrolling grid is not.

`CatalogGrammarTest` reads the catalog as a grammar and holds four more things that need no
document to check: every required slot accepts something, every component can be closed by a finite
subtree, every component is accepted by some slot, and every trait a slot names is carried by some
component. The last found two slots nothing could ever fill — the snackbar host's `SnackbarContent`
and the search bar's `SearchResults` — and, in `wear-m3`, a button whose borrowed `leadingIcon` slot
asked for `m3/icon`'s trait after `m3/icon` had left the catalog. The `remote-m3` widget background
is the one known survivor: the brushes it takes are not in that reviewed subset yet, and the test
names it rather than tolerating it.

## Generating trees, and refusing them

Two tests in `:ui-builder` ask the catalog's own question from both ends.

[`GeneratedDocumentTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/GeneratedDocumentTest.kt)
builds documents the way a person does — select a node, pick a component, press Add — and asserts
each one is valid at every tier it could be wrong at: structural, catalog, export, and round trip.
It does not write nodes directly, and that is the point. A generator that assembled JSON could
produce shapes no sequence of editor operations reaches, and every failure it found would need
triaging for whether the document was reachable at all before it meant anything. Where a child lands
is not the generator's decision either: it asks `dropTarget`, which is the call the palette's Add
makes, and inserts at exactly what comes back — `InsertComponent` refuses a target the current
selection does not resolve to, which is what stops a stale panel writing into a slot that has moved.

Seeds are fixed, so a failure reproduces from the seed alone. The catalog is the grammar, so a
component or slot added to it is generated the day it is declared.

[`EditSequenceTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/EditSequenceTest.kt)
runs the operations that are not inserts — move, delete, duplicate, wrap, unwrap, copy, cut, paste,
property writes, undo and redo — in whatever order a seeded walk puts them, against whatever the
selection happens to be, and without consulting the `can…` guards. Those guards decide what the
toolbar offers; the reducer is what has to hold when something is asked for anyway, which is what a
stale panel, a keyboard shortcut or a racing collaborator produces. Two invariants after every
event: the document changes **only** through an accepted command, and every document it passes
through is valid — each intermediate one, not just the last, because that is what a collaborator's
screen, an autosave and an export all see. Undo has its own: it restores the previous document
node-for-node. Both tests assert a floor on how many events actually edited and how many were
refused, so a walk that quietly became a no-op fails rather than passes.

[`MutatedDocumentTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/MutatedDocumentTest.kt)
is the other direction: it takes a generated document, breaks exactly one rule, and asserts the code
that names it — an incompatible slot child, a required slot emptied, a slot past its maximum, a
dangling child id, an undeclared slot or component, a missing required property, an enum value the
catalog does not allow, an undeclared modifier, a node in two slots, a cycle, a root count other than
one, an unreachable node. One tier at a time is what makes a failure readable: a document broken
three ways is refused whatever the validator actually noticed. Those mutations write the document
directly on purpose — the question there is what the *validator* does with a bad document however it
arrived, and the editor's own doors are asked separately, where being refused is not enough and the
document has to come back unchanged.

What they found, each pinned by the test that found it so the list cannot grow quietly:

- **`m3/button.leadingIcon` was a slot nothing could use.** Add could never reach it —
  `findDestination` takes the first slot that accepts and has room, and this one sat behind an
  unbounded `content` that accepts an icon too — the renderer never drew it, neither exporter ever
  wrote it, and no fixture used it. Meanwhile its mere presence made every design holding a button
  unexportable, because an insert writes an entry for every slot the catalog declares and the
  shipped record's `Button` has no such parameter, so the export refused on the key being *present*,
  empty or not. Dialogs refused three times over, being seeded with two buttons. The slot is gone:
  Material's `Button` takes one content lambda and an icon goes inside it, beside the label
  ([#430](https://github.com/yschimke/compose-preview-server/issues/430)).
- **A progress indicator inserted from the palette could not be exported.** It arrived determinate,
  and a determinate one takes `progress: () -> Float`, a lambda no value in the document vocabulary
  can be. Fixed by [#435](https://github.com/yschimke/compose-preview-server/pull/435), which the
  suite noticed on its own: the case had been pinned as a known failure, so the fix turned the test
  red and asked for the pin to go. Nothing is pinned there now — every recorded component exports
  after a palette insert, unconditionally.
- **Nothing bounds the root count before export**, so a design with zero or two roots can be stored
  and never exported ([#429](https://github.com/yschimke/compose-preview-server/issues/429)).

Neither of the first two was caught by the checked-in goldens, and the reason is worth keeping in
mind when adding one: the goldens replay **hand-authored operation lists**, which write only the
slots and properties somebody meant to write, where an insert writes everything the catalog
declares. The Jetcaster fixture exports three buttons happily because none of them carries the key
the editor would have written.

### An empty slot entry outlives its declaration

Withdrawing `m3/button.leadingIcon` would have invalidated every design anybody had saved, because
every button the editor ever inserted carries an empty `leadingIcon` key — and at the time the
persisted store was validated on load, so "invalid" there meant the service did not start. (It no
longer does; see [validity is checked late](#validity-is-checked-late-so-the-service-always-starts)
below. Both changes are worth having: one keeps documents valid, the other keeps one that is not
from taking the server down with it.)

So an entry with **no children** for a slot the catalog does not declare is not a finding, in
`CapabilityValidator` and in the server's catalog validation alike. It says nothing is in that slot,
which is exactly what leaving the key out says, and it lets the catalog drop a slot without
invalidating documents that never used one. A child in an undeclared slot is still `UNKNOWN_SLOT`,
because that child would otherwise be silently dropped.

### Validity is checked late, so the service always starts

The persisted store used to be validated in the service's constructor: every stored design against
the current catalog, the current limits and the topology rules, throwing on the first one that
failed. So one design could stop the whole server coming up, in a store that may hold a thousand.

The shapes that trigger it are ordinary rather than exotic. A catalog revision moves and every
design pinned to the old one stops resolving. An operator stops serving a catalog, or tightens a
per-design node limit. Withdrawing a slot did it, which is how this was found. In every one of those
the blast radius — the whole service — had no relationship to the cause.

So the check moved to the point of use. Every design loads; one that cannot be served is recorded
with the reason and **every request naming it is answered with that reason** — `CATALOG_UNAVAILABLE`
when its pin no longer resolves, `INTERNAL` otherwise — including subscribing, which is the other
door in. It still appears in the design list, because a design nobody can open is bad and one nobody
can see is worse. `diagnostics().unusableDesigns` counts them, so an operator learns they exist
without opening one, and `adminUnusableDesigns()` names them — behind the count, on the admin
screen, and in a line the runner logs at startup for each one. `UnusableStoredDesignTest` in
`:ui-builder-runtime` holds all of that.

### Quarantine is a workflow, not a waiting room

Holding a design back is only half an answer. A design nobody can open, that an operator can only
delete, is a document destroyed by a rule change — and the rule is at least as likely to be wrong as
the document. So the operator's surface carries the whole loop, on the admin token alone:

| | |
|---|---|
| `GET /admin/ui-builder/designs` | lists every design; a held-back one carries `unusableReason` |
| `GET /admin/ui-builder/designs/{id}/document` | the stored document, as JSON |
| `PUT /admin/ui-builder/designs/{id}/document` | a repaired document, put back |
| `DELETE /admin/ui-builder/designs/{id}` | retire it |

Download, edit until it satisfies the rule that changed, put it back. `adminDesignDocument` reads
what is on disk and consults neither the catalog nor the limits, which is what makes it work on the
design that needs it most; the ordinary export renders **through** the catalog, so it cannot reach a
design held back because of one. `adminRepairDesign` then asks the candidate exactly the question
the original failed, so a file that does not repair the design comes back with what is still wrong
rather than being stored and quarantined again. On success the design is served in that process —
needing a restart here would put back a smaller version of the trap this all exists to remove — and
the quarantine set is maintained rather than frozen, which also means a deleted design does not
leave a stale entry answering its id with a catalog error.

Repair is deliberately closed to a design the host serves normally. That one has live editors, a
revision history and subscribers reading a sequence, and it is edited through `ApplyOperation` with
an actor and authorization. A quarantined design has none of those by construction — it refuses
every request that names it — so its history describes a document this build could not load, and is
replaced rather than extended, with the sequence continuing upward so no client can mistake the
repaired design for the old one.

**Should a rule change carry a migration instead?** Sometimes, and the test is whether the repair is
mechanical. A rule that renames a field, or splits one into two, knows exactly what the old document
meant and should rewrite it on load — quarantining there is a worse answer than migrating, because
every operator would perform the identical edit. A rule that *narrows* what is allowed, as
withdrawing `topBar`'s acceptance of a lazy grid did, does not: there is no correct rewrite, only a
choice between moving the node, replacing it, and deciding the rule went too far. That is a
judgement, and quarantine is how a judgement gets routed to a person instead of being guessed at by
a migration. Filing the judgement as an issue or a PR from the host itself was considered and is not
worth it: it needs credentials the host should not hold, and it would publish a user's document into
a public tracker to ask a question the admin screen already asks in front of the person who can
answer it.

**Integrity is the line this does not cross.** A state file whose checksum does not match, that is
truncated, or that declares a persistence format this build cannot read is still refused outright by
the storage layer before any of this runs, and `restoreBackup` is the recovery. Trusting a file that
failed those checks would be worse than not starting; carrying a design the catalog outgrew is not.
The format migration is not gated on design validity either, for the same reason the startup check
was not kept: an unservable design must not be able to block the one path an operator has to move
the store forward.

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

## The component menu, and what a variant is

The insert panel used to be one flat list under three headings taken from a component's **role** —
`Scaffolds`, `Containers`, `Composables`. That is the question a *slot* asks ("what may go in here"),
not the one a person asks ("where is the card"), which is how `Containers` came to hold a tab row, a
card, a dialog and a plain `Row` under one word.

It is now the tree the published catalog draws beside its grid, drawn the way that one is: an **All**
pill carrying the whole count, sentence-case shelf rows with a solid twisty and an accent bar down
the open branch, a muted count at the end of every row, and an indent rule down each shelf's
children. The families are not invented here — they are the vocabulary
`@file:CatalogGroup(section = …)` already uses in m3-catalog.

**The catalog declares its own shelves.** The obvious home — a field on `ComponentCapability` — is
not available: the wire shape is `CatalogCapabilityV1` in `compose-preview-contracts`, decoded by
`CurrentM3UiBuilderCatalogExecutor` with `ignoreUnknownKeys = false` on purpose, so a key invented
here is a catalog that repository cannot read. The first pass answered that with a hardcoded table
in `:ui-builder`; `UiBuilderPreviewSurfaces` answered the same question better, and the menu now
follows it into `statusSemantics` under `componentMenu`, read back by
[`ComponentMenu.from`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/ComponentMenu.kt).

The difference is not tidiness. A table in `:ui-builder` can only describe catalogs whose component
ids *this* repository knows, so `wear-m3` and `remote-m3` fell back to the role headings however
well-organised their components were. `wear-m3` now declares Wear's own families — a watch app is a
screen holding a list, so those lead, and `Text inputs` does not exist there at all — plus the nine
of its components that carry variants. A catalog that declares nothing still gets the role headings,
which is what every catalog had before any of this.

Everything falls back, per entry: an undeclared component takes its kind label, a `variantProperty`
naming a property the component does not declare means no variants, an unreadable declaration means
the plain menu. A menu is presentation, and a wrong one must never be the reason a component cannot
be inserted. `CatalogMenuTest` and `WearM3ScreenCatalogTest` hold each declaration against the
components beside it.

One structural difference from the catalog's own tree is the data rather than the design: the catalog
splits `Progress Linear` and `Progress Circular` into two components under a `Progress indicators`
group, where the builder has one `m3/progress-indicator` whose `variant` property is `linear` or
`circular`. The builder's tree is the same shape one level shallower — what the catalog spends a
group level on, this spends a variant level on.

**The thumbnails are rendered, not baked.** The catalog's tree shows a prebaked PNG per row because
that page has the pixels on disk. The builder has something better: the renderer that is about to
draw the component for real. So a row draws `UiBuilderEditorReducer.previewDocument` — the component
inserted into an empty frame by the same `InsertComponent` the row's Add dispatches — shrunk with a
`graphicsLayer` after laying out at a sensible size. A thumbnail therefore cannot disagree with what
pressing Add does; there is no generator task to re-run when a starter default changes, no PNGs in
the repository, and a catalog nobody has baked artwork for still gets pictures. It is also the grip:
you drag the picture of the thing you are placing, which affords two things in the width of one.

Two details are load-bearing and neither is obvious. The frame pins **no** `density` or `fontScale`,
where every other fixture pins `1.0`: those pin a document rendered *as the whole surface*, where a
dp is a pixel, but a thumbnail is a subtree of a panel whose dp are the platform's, and pinning 1.0
laid a Button out at a quarter size inside a box measured in real dp — a four-pixel dash. And the
drag gesture sits on an overlay **above** the picture, whose own semantics are cleared: a Switch
drawn in a thumbnail is a real Switch, and it would otherwise eat the press meant to start a drag
and publish itself to a screen reader as a control the palette does not have.

**A variant is one property, named by the declaration.** `ComponentMenu.variantPropertyOf` says which
of a component's properties enumerates its variants — `m3/card.variant`, `m3/button.style`,
`m3/time-picker.mode` — and the rows under a component are that property's `allowedValues`, in
declaration order, the first marked `default` because that is the one `defaultEncodedValue` already
writes. Adding a variant is one `InsertComponent` carrying it, so a collaborator sees an outlined
card appear rather than a filled one that changes a frame later; the variant is written over the
component's starter content rather than instead of it, so the card still arrives holding something.

Declared rather than inferred, because every heuristic for "the variant property" is wrong somewhere
in this catalog: `m3/icon.iconKey` is an enum of forty-seven icons and no more a variant than
`layout/row.horizontalArrangement` is, while `variant` and `style` are the same idea under two names.
A stale declaration degrades rather than fails — an unknown property name, or one with no allowed
values, means the component offers no variants, exactly as it did before the field existed.

The search matches everything a row shows, variant labels included, and opens what it matched: a
match hidden behind a collapsed heading reads as no match. Collapsing something and then typing does
not spend the collapse — it comes back when the field is cleared.

| Before: grouped by role | After: the catalog's families, each row drawing itself | Filtered to a variant name |
| --- | --- | --- |
| ![The insert panel under Scaffolds, Containers and Composables headings](../../renders/ui-builder-component-menu/menu.before.png) | ![The same panel under Scaffolds, Layout, Navigation and Actions headings, each row showing a rendered thumbnail of its component](../../renders/ui-builder-component-menu/menu.thumbnails.after.png) | ![The panel filtered to "filled", four components open showing their variant rows, each variant drawing a visibly different button](../../renders/ui-builder-component-menu/menu.variants.after.png) |

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

## Slider and progress

`m3/slider` and `m3/progress-indicator` are the decimal half of what the selection controls did for
flags: a slider **writes** a declared decimal variable and an indicator **reads** one, so between
them they cover both directions of the same seam.

Two things in the pair are worth stating.

An **indeterminate** indicator is not a value. It is Material's other overload — the one you call
without a progress lambda — so the flag picks the call rather than a number, and the canvas draws it
at its first frame because the document environment freezes animation. A render that animates is a
render nothing can diff.

And a slider's `onValueChange` **assigns** rather than dispatching an action. The document's action
vocabulary writes declared values, and a slider's change already carries the value, so the assignment
is the action. An unbound slider assigns nothing, exactly as an unbound text field does.

Adding them also fixed a rule that had been quietly wrong since the first bindable property: a
declaration like `["number", "object"]` — "a number, or a read of a state variable" — was judged as a
whole and came out `Unsupported`, so a slider's entire range and a text field's `value` had no
editor at all. The boolean branch beside it had already learned this for `["boolean", "string"]`;
the number and text branches now read the same way, ignoring the object half that only a binding
writes.

### Visual evidence

The slider and all three indicator forms in
[`renders/ui-builder-slider-progress/`](../../renders/ui-builder-slider-progress/README.md).

![A slider, a linear indicator, a circular indicator and an indeterminate one](../../renders/ui-builder-slider-progress/slider-progress.after.png)

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
