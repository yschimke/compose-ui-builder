# The canvas, the frame, and the variants

Three questions the builder has always answered with one picture:

| Axis | The question | Where the answer lives |
| --- | --- | --- |
| **Document shape** | what is *in* this design — one screen, or several items? | `UiBuilderDocument.roots` and the tree under it |
| **Frame** | what is the design *measured in* — how wide, how dense, which theme? | `DesignEnvironmentV1` |
| **Viewport** | how many pictures do I *look at* at once? | `PinnedDesignCanvas` |

They were pinned together at 1:1: one design, one device, one picture. This document is the *why*
behind unpinning them, and the rule it settles is that they are unpinned **separately**. A design
holding several items is a fact about the document. Looking at a design on three devices at once is a
fact about the viewport. Neither implies the other, and a feature that moves one while dragging the
other along is the thing this document exists to refuse.

## 1. Several items is a node, not several roots

A design that holds a card, a dialog and a chip side by side — assets on a board rather than a
screen — is one tree whose root is a container. It is **not** a document with three roots.

`roots` stays at most one, which it has always been, checked by `requireValidTopology` on the client
and `validateTopology` on the server. Nothing downstream learns a new shape: the renderer, both
Kotlin exporters, `ScreenDocumentProjection` and `validateDocumentForExport` see the `layout/column`
they have always seen, because that is what it is.

### Why not `roots.size > 1` as the mode

It is the tempting answer — the list is already there, it round-trips through the wire, the hash,
the delta and the undo log, and it needs no new field in a contract this repository cannot change
([#534](https://github.com/yschimke/compose-preview-server/pull/534) proposed exactly that). It was
refused for four reasons that outlast the elegance:

1. **The arrangement has nowhere to live.** Several roots are not laid out by anything, so something
   downstream has to invent a column — and then that column is synthetic: never in the document, not
   addressable by any operation, its spacing a constant in the code. "Put these 16 dp apart" and
   "move this one up" are the first two things anybody asks of a board, and neither has a home.
2. **Four consumers have to agree about a picture the document does not contain.** The renderer, the
   two exporters and the projection each have to be told what several roots mean. One shared helper
   keeps them agreeing today; it is still four places that must not be forgotten tomorrow.
3. **It makes an insert change the meaning of the whole document.** Under a root-count mode, adding
   one item silently converts a screen into something that exports as a `Column`. As a node, the
   conversion is a node you can see in the layer tree, select, and delete.
4. **It spends the root list on something a node already does**, and then has to defend the spend
   against export, which requires exactly one root
   ([#429](https://github.com/yschimke/compose-preview-server/issues/429) is that dead end). Keeping
   `roots` at one means the dead end is never reachable rather than reachable and then repaired.

### Why not a new `layout/board` component

Because `layout/column` already is one. Its `children` slot accepts the `Scaffold`, `Container` and
`Leaf` roles with `AnyContent` traits, so a scaffold, a card and a chip are all legal siblings in it;
it declares `verticalSpacingDp` and `horizontalAlignment`, which is the whole of the arrangement; and
all three catalogs (`m3-catalog`, `wear-m3`, `remote-m3`) already carry it. A new component would be
a catalog change, in three catalogs, to express what one of them expresses already.

**The board is a `layout/column` with board defaults**: `verticalSpacingDp` 24, `horizontalAlignment`
`center`, `Modifier.fillMaxWidth()`. 24 rather than a tighter number because the gap is what says
*these are separate things* — a board holding a card and a dialog 8 dp apart reads as one screen laid
out badly. They are defaults, not rules: they are properties of a node in the document, so the
inspector edits them like any other.

### The one thing that had to change: when the root count is checked

Wrapping an existing root takes two operations — insert the board beside it, then move it inside —
and the document has two roots in between. The client reducer checked the root count after *every*
operation, so the intermediate state was refused and the transaction was impossible.

The server has always checked it once, after the whole command
(`PersistentUiBuilderService.kt`, the `validateTopology` call after the mutation loop). The client now
agrees with it: `requireValidPlacement` — unknown nodes, exactly one location per node, no cycles —
runs after every operation as before, and `requireSingleRoot` runs once at the end of the command.

That is the honest place for it. **Every other rule in that function is about a node; this one is
about the document.** A command is the unit that commits, so a command is the unit a document-level
invariant answers to. A sequence ending in two roots is rejected exactly as before, with the same
code and the same message — `CollaborationConvergenceTest` still pins it.

### What "Add beside" does

A switch in the insert panel, under the line that says where the next Add lands. Off — the default,
and every design's behaviour before this — an Add fills the selected layer's first accepting slot.
On, an Add appends a top-level item:

| The root today | What an Add beside does |
| --- | --- |
| nothing (empty design) | inserts the component as the root, exactly as an ordinary Add does |
| a `layout/column` | appends into it — the root is already a board |
| anything else | wraps it in a board column, then appends beside it |

It is a **tool mode**, not a property of the design: `UiBuilderEditorState.addBeside`, not stored, not
shared with a collaborator, not undone, and off again when the design is reopened. What it produces —
the board node and its children — is in the document for everyone to see. It does survive a document
*arriving*, though, the way the selection and the clipboard do: every accepted edit and every
collaborator delta rebuilds the editor state from the authoritative document, so a mode dropped there
would switch itself off one Add after being switched on.

### What a board must not change about the design under it

A board is a container the editor put there, not one the author reached for, so a question that was
true of the root before an Add beside has to stay true after it. Two of them were not, and both are
the same mistake — code that asks about `roots` when it means *the top of the design*:

- **The theme host.** The `m3/surface` carrying the palette, the type scale and the corner radius was
  looked for in the root list by both the renderer and `themeSettings`, so wrapping a themed screen
  dropped its theme from the canvas and made Apply theme refuse the document for having no root
  surface. Both now ask `topLevelNodes`, which is the roots, or the board's items when a board is the
  root. One level, deliberately: a surface three cards deep was never the theme host.
- **Which emitter writes the design.** Both record-free emitters route on the root component id, so a
  wrapped Wear screen would quietly stop being one. That is what the refusal above is for — and why
  an empty design takes the component as its root rather than opening a board around it, which would
  have made the *first* Wear item on a new design the same silent conversion with nothing to refuse
  over.

  Refusing the *wrap* is only half of it, because a board that already exists has no wrap left to
  refuse: a design whose first Add beside was an ordinary layout would then accept a Wear scaffold as
  its second item. So the refusal asks about the component as well as the document, against
  `RecordFreeExport.ROOT_ONLY_COMPONENT_IDS` — derived from the emitters themselves, the way
  `CATALOG_SYSTEM_IDS` already is, because a hand-kept list drifts towards claiming a component is
  placeable while its emitter still demands the root.

### Every insert path, or the panel is lying

"Adds beside the design" is a promise the whole insert panel makes, so every row in it has to keep
that promise. The Remote Compose rows did not: they were offered under Add beside — a top-level item
needs no compatible slot — and then resolved an ordinary drop target after their fetch came back, so
pressing one either refused or landed inside the selection while the panel said otherwise. Both paths
now ask one `besideDestination` where a top-level item goes, which is also the only place that knows
whether this design has a board yet. A played `.rc` document is exactly the kind of asset a board is
for.

Deliberately a switch rather than a fallback inside the ordinary insert. An Add with no compatible
slot stays *refused*, because turning that refusal into "then it becomes a second item" would make a
full scaffold grow a neighbour every time somebody added a chip it had no room for.

### Free positioning, and why it is no longer walled off

An item's place on a board is its order in the column. Free x/y is not in this change, but it is no
longer *unreachable*: a board whose component is `layout/box` rather than `layout/column`, with
`offset` on each child, expresses it exactly — and `offset` is already in `layout/box`'s declared
modifier capabilities, and a modifier is document content rather than a wire field. Under a
root-count mode the same feature needs an offset per root, which is a field in a closed contract
another repository publishes. **Choosing a node over the root list is what turns that wall into an
afternoon.**

## 2. The frame is not a device

`DesignEnvironmentV1` describes a measuring surface: a width, a height, a density, a theme, a locale,
a layout direction. A *device* is one way to fill those in, and `UiBuilderDevicePreset` is a list of
such ways that the render lane can actually produce.

The inspector used to present the two as the same thing, which is wrong in both directions: a
hand-typed 1400 × 1000 frame was shown under a heading that claims a device, and a design of loose
assets appeared to be a phone. So the section is **Frame**, a device preset is a way to *set* the
frame, and when the frame matches no preset it says so rather than implying one.

Nothing is hidden on a board. The frame still applies — the items are laid out down the middle of
that width, at that density, under that theme — so hiding the width, the density or the presets would
remove controls the picture still obeys. What changes is only the claim: a frame that matches no
device is a frame, not a device.

## 3. Variants are panes, not documents

"Show me this screen on a phone and a tablet, in light and dark" is a question about the viewport. It
must not become several documents, several roots, or several stored environments — those all answer a
different question and cost a merge.

**One document, one stored environment, N panes.** The workspace already had two panes for exactly
this reason: the editing pane draws the design's whole extent, and `ConstrainedFramePane` beside it
draws the same document clipped to its frame, read-only, with its own `renderSessionId`. A variant
pane is that same seam, parameterised by an environment *override* instead of a height clamp.

### Where the device list comes from: `exportDevices`, already stored

`DesignEnvironmentV1.exportDevices` is a stored, shared, wire-carried list of device ids the design
claims to work on. Before this, exactly one consumer read it —
`ScreenGeneratorComposeExportExecutor.previewFor`, which turns it into `@Preview(device = …)` on the
generated screen — and the editor never drew it. A design could therefore claim three devices and show
its author one, and the two decisions were made in different places with neither showing the other.

The variant strip is that list, drawn. **The set you look at is the set the export writes**, which is
the whole reason to seed it from stored state rather than from a viewer-local list of devices.

### One editing pane, and the rest are mirrors

Exactly one pane accepts edits: the extent, at the design's own frame. Every variant pane is
read-only — no selection overlay, no hit-testing, no drop target, no comment pins.

This is the load-bearing rule of the feature, not a limitation of the first version. "Which variant
did my edit land in?" is the question that makes multi-variant editors confusing, and a design has one
document: an edit made on the tablet pane is an edit to the same tree the phone pane draws. Making
every pane editable would not give the author more power, it would give them a coordinate space per
pane and one shared outcome.

### Theme, font scale and direction are unstored view toggles

Devices come from the document because the document already carries them and the export already
writes them. Dark, RTL and a large font scale do **not**: they are toggles on the strip, held in
`UiBuilderEditorState`, not stored, not shared, not undone.

They could have been stored sets — an `exportThemes`, an `exportFontScales` — and that is a field in
`DesignEnvironmentV1`, which is published from `compose-preview-contracts` and closed to this
repository (the wall [`UI_BUILDER_REFERENCE_OVERLAY.md`](UI_BUILDER_REFERENCE_OVERLAY.md) hits in its
point 2). Rather than smuggle them through a field that means something else, they are honestly what
they are: a way of *looking*, which costs nothing to be wrong about and is reversible by switching it
off. If one later earns a place in the document, it graduates when the contract can carry it.

### Two people wrapping the same root at once

The wrap is an insert plus a move, and it does not rebase. Two collaborators who press Add beside on
the same *non-board* root from one base revision each build their own board; the second command's
move then pulls the root out of the first board and leaves two of them at the top, so
`requireSingleRoot` refuses the whole second Add — including the item that motivated it.

That is narrower than it sounds and it loses nothing: the refusal is a rejection, not a corruption,
and the second author's next Add appends into the board that now exists and succeeds. It is still a
gap against the concurrent-insertion guarantee in
[`UI_BUILDER_PRODUCT_SPEC.md`](UI_BUILDER_PRODUCT_SPEC.md) ("concurrent insertions retain both nodes
in server order/position-key order"), and it is the one place this feature does not meet it.

Closing it means rebasing a command against a concurrent one — recognising at apply time that the
root has become somebody else's board and appending into that instead of wrapping again. The reducer
rebases nothing today, so that is a change to the collaboration model rather than to this feature,
and it belongs with the structural-versioning work the same review surfaced.

## What this is not

- **Not a second document.** A variant is the same tree under a different frame. There is nothing to
  merge, nothing to keep in sync, and no way for two variants to disagree about content.
- **Not free positioning.** See §1 — reachable, not reached.
- **Not a device claim on a board.** A board is measured by a frame like everything else; it just
  does not pretend that frame is a phone.
