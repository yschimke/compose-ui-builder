# Tunables: dragging a few numbers to try a design

A design often turns on three or four numbers: the gap between cards, a corner radius, the width
of a carousel item, how much padding sits around the content. Typing each one into the inspector
and waiting for the canvas tells you whether one value is right. It is a poor way to *find* the
right value. A **tunable** is a named number with a minimum, a maximum and a default, linked to the
properties it drives. Drag its slider and the canvas and every preview pane redraw as you drag.

## What a tunable is

```kotlin
DesignTunable(
  name = "Card width",
  minimum = 96.0,
  maximum = 200.0,
  default = 128.0,
  integer = false,
  targets = listOf(
    TunableTarget.Modifier("podcast-card-android", index = 0, type = "size", field = "widthDp"),
    TunableTarget.Modifier("podcast-card-google", index = 0, type = "size", field = "widthDp"),
  ),
)
```

A target is one of two things:

- **A plain numeric property**, `{"type": "float" | "int", "value": …}`. A property that holds a
  state binding, a component argument or an object value (`contentPadding.topDp`) is not a
  plain number and cannot be tuned.
- **One numeric field of one modifier**: the `widthDp` of the `size` at index 0 in the chain. The
  index *and* the type must still match. If the chain changes so that index 0 is now a `clip`,
  the link stops resolving. It never moves on to tune whichever modifier took that place.

One tunable can drive several targets, which is the point of naming it: "card width" is one
decision about two cards. A target follows only one tunable at a time, so linking it to a second
tunable unlinks it from the first.

## How it is used

- **Tune** beside any numeric property or modifier field in the inspector opens a menu. Choose
  *New tunable from this value* to start a tunable from that field, *Drive from …* to link the
  field to an existing tunable, or *Stop tuning*. A field that is driven shows `≈ <name>` there
  instead.
- A new tunable is seeded from the field. Its default is the field's value. Its range runs from
  zero, or below the value if the value is negative, to well above the value, clamped to the
  catalog's own bounds. A 16 dp padding gets `0..48`, not `0..10000`. `alpha` and `rotate`
  modifiers get their natural ranges (`0..1`, `-180..180`). An `int` property moves in whole
  steps. Other values snap to a step that suits the range: whole numbers for a range of 20 or
  more (every dp range worth dragging), tenths below that, and hundredths under 2 (an alpha or a
  weight). A drag never writes `254.11764526367188` into the design.
- The **Tune** card floats over the top-left corner of the canvas whenever the design has a
  tunable. It shows one slider per tunable, with its value and what it drives. The ⋯ menu edits
  the name, range and default, returns the slider to its default, unlinks a target or removes
  the tunable.
- **Apply** writes the current values into the targets and makes them the new defaults. **Reset**
  puts every slider back on its default.

A number **design token** can be tuned as well: **Tune** on its row in the Theme panel puts a
slider over every property the token binds. Its targets are re-read as the design changes, so a
list added later is tuned with the rest. See [`UI_BUILDER_DESIGN_TOKENS.md`](UI_BUILDER_DESIGN_TOKENS.md).

A design is offered at most eight tunables (`MAX_DESIGN_TUNABLES`). That is a handful of knobs,
not a second inspector.

## Dragging is a way of looking

The rule behind the design: **while a slider moves, the document does not.** Nothing is written
until the author presses Apply. Until then:

- the canvas and the preview panes draw `document.tuned(tunables, values)`, a copy with each target
  holding its tunable's value. The copy is the same instance as the document when nothing is
  tuned, so a design without tunables costs the canvas nothing;
- the revision does not move, nothing is submitted, collaborators see the stored design and undo
  is untouched;
- an export, the code pane and the problems panel all read the stored document. What ships is
  always what is stored.

This is the same split as the unstored variant axes in
[`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](UI_BUILDER_CANVAS_FRAMES_VARIANTS.md). It also makes
tuning cheap: a slider fires dozens of values a second, and each one would otherwise be a command,
a revision, a round trip to the host and an undo step.

Because the preview panes draw the tuned copy too, a tunable combines with the variants. You can
drag the card width and watch it at once on a phone, a tablet, in dark mode and at a 1.5× font
scale. That is the "try different configurations" loop this feature exists for.

## Applying

Apply goes through the reducer's ordinary command path, so the catalog validators judge each
value exactly as they judge a typed one. It writes **one lane per event**: the property writes,
then the modifier writes. There are two reasons. The collaboration reducer undoes properties and
modifier chains in separate lanes and refuses to compensate a batch that mixes them
(`UNSUPPORTED_COMPENSATION`). And a host is sent the one command an event produced, so two
commands from one event would leave the second applied locally but never submitted. When the
first `ApplyTunables` leaves a lane unwritten, the editor dispatches a second one straight away.
A configuration that touches both lanes therefore takes two undos to take back, and one that
touches a single lane takes one. If a command is refused, Apply stops there and the sliders stay
where they were, so nothing the author dragged is lost.

A target that only takes whole numbers (an `int` property) moves the tunable it joins onto whole
steps. Otherwise the slider would show `0.5` while that target drew and wrote `1`.

## Not stored, yet

Tunables are editor state, held on `UiBuilderEditorState` beside `variantAxes`. They survive every
document that arrives (an accepted edit, a collaborator's delta), less any target that no longer
resolves. They are dropped when a different design opens: a node id in another design is a
different node. They are not saved with the design, shared with collaborators or kept across a
reload.

They would be stored if the wire could carry them. Every document field in the protocol is typed
by compose-preview-contracts, which this repository cannot change. The obvious home is a numeric
state variable with a range, but `StateVariableV1` has `type`, `valueType`, `nullable`,
`initialValue` and `persistence`, and no minimum or maximum. A state binding would also change
what the export writes, putting a `mutableStateOf` into production code for a number that was only
being tried. Storing them needs a design-level field in the contract, for example a
`tunables` map of `{min, max, default, targets}`. The editor's model is already shaped like that
field. When the contract can carry it, it graduates the way the variant-axis document says such
toggles should.

## Where it lives

| Piece | File |
| --- | --- |
| Model, substitution, targets | `ui-builder/.../editor/UiBuilderTunables.kt` |
| Tune card and the inspector's Tune button | `ui-builder/.../editor/UiBuilderTunePanel.kt` |
| Events and reducer (`TuneTarget`, `SetTunedValue`, `ApplyTunables`, …) | `UiBuilderEditorEvents.kt`, `UiBuilderEditorState.kt` |
| Tests | `ui-builder/src/jvmTest/.../TunableParametersTest.kt` |
