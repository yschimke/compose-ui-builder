# The fidelity ladder: mock, real, platform

The workspace draws a design three times, and the three are not three views of one thing. They are a
**ladder**, and each rung trades something away to buy something the rung above it cannot give you.
Which rung you are on decides what you are allowed to conclude from what you see.

|  | 1 · Visual editor | 2 · Preview | 3 · Native |
| --- | --- | --- | --- |
| **Components** | Mock (Wasm) | Real (Wasm) | Real (platform) |
| **Interaction** | Live edits | Live edits | Live preview, where the host can stream one |
| **Frames** | Single | Multiple | Single |
| **Costs** | nothing | nothing | a host round trip |
| **Source of truth** | the document | the document | the generated source (the exported document, where there is no compile lane) |

Read it left to right: fidelity goes up, and what you can *do* goes down. You author on rung 1,
check on rung 2, and confirm on rung 3.

## The workspace opens on authoring plus comparison

Every design opens with **Visual editor** and **Preview**. Native is never opened implicitly, even
for a catalog whose browser rendering is approximate: compiling through the host is an explicit,
potentially expensive action, not a cost of opening a document.

There is exactly **one editable view**. It is the Visual editor's direct, unrolled canvas. A preview
frame is a read-only mirror; device, resolution, scheme, direction and font-scale variants belong
there precisely so an edit never has an ambiguous target. Native is also a single read-only view: it
confirms one selected frame at platform fidelity after the free preview has compared the variants.

Wear widgets follow the same rule. Their editor uses the largest rectangular host so every widget
node remains direct and selectable. Their Preview shows the three launcher hosts — **Pixel Watch**
(squircle), **Samsung** (round), and **Rectangular** — while Native renders only the selected host.

## 1. The visual editor is the one that is allowed to lie

This is the load-bearing asymmetry of the whole workspace, and it is deliberate rather than a gap.

An editing surface has a job that a faithful one cannot do: it has to show you the thing you are
editing, including the parts a device would not show. The clearest case is a scrolling column. A
`LazyColumn` on a 914 dp phone shows you rows one to four; the author is working on rows nine to
twelve as much as on the first four. So the canvas draws the **extent** — the frame's width, the
content's full height, unrolled — and that is the surface edits land on
([`CanvasExtentLayout`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas/CanvasExtentLayout.kt)).
It is not what the device shows. That is the point.

The same licence covers everything else the canvas puts on top of the design: the selection overlay,
drop targets, the hover editor, comment pins, collaborator cursors. None of it is in the design, and
all of it is in the way of using the design.

It covers one thing *inside* the design too, for the same reason. An adaptive scaffold on the canvas
draws **every pane it declares, at every canvas width** — the full expanded, tablet experience —
rather than collapsing the way the real component would
([`UnfoldedSupportingPaneScaffold`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas/UiBuilderRenderer.kt)).
A collapsed pane is not merely a smaller picture: it is a subtree that cannot be selected, dropped
into or edited, and the width that would have collapsed it is the canvas's, which is a window nobody
ships. This is the unrolled column again — show the author the thing they are authoring — and the
pane beside it is what says which parts a device actually gets.

Components on this rung may be **adapters or placeholders**. What matters on rung 1 is that a node is
**there**, is **selectable** and **takes a drop** — not that it is the component the export names.

This is not a licence to fabricate. `AGENTS.md` forbids hand-assembling a lookalike to stand in for a
library the canvas cannot link, and the renderer keeps that rule with two different shapes:

- an **adapter**, where the canvas has a real component that carries the same contract —
  `CompatibleHorizontalCarousel` over a `LazyRow`, because the real lazy carousel cannot expose all
  authored children in the unbounded editor extent. The node draws, and what it draws is a real
  component; Preview switches to `HorizontalUncontainedCarousel`;
- a **named placeholder** ([`NativeOnlyPlaceholder`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/canvas/UiBuilderRenderer.kt)),
  where it does not. The node says what it is, keeps its children so the tree is still navigable, and
  makes no claim about how it looks.

The second is the honest answer for a component with no counterpart, and replacing it with a replica
assembled out of Material 3 pieces would produce an impression nothing in this build can check. A
catalog whose components the canvas cannot draw gets its fidelity from rung 3, not from a replica
maintained here — see
[`UI_BUILDER_WEAR_SCREEN.md`](UI_BUILDER_WEAR_SCREEN.md#the-line-a-component-is-never-faked-so-it-can-run-in-wasm).

**What the editor may never lie about** is the document. Every node it draws is a node in the tree,
at the position the tree gives it, with the properties the tree carries. The lie is always about
*presentation for authoring*, never about content.

## 2. The preview is the same browser, telling the truth

Rung 2 is the same renderer with the authoring apparatus taken off it — and it is where the **real
components** belong. It exists because rung 1 cannot answer two questions:

- *Does it fit?* The preview composes each frame **bounded**, so a `LazyColumn` is lazy, scrolling is
  the design's own, and content past the frame's edge is content the device does not show
  ([`ConstrainedFramePane`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/editor/UiBuilderEditor.kt)).
  A design that overflows here overflows on the device.
- *Does it adapt?* This is the rung that earns the **multiple frames**: every device the design
  claims in `exportDevices`, plus the unstored axes (dark, RTL, large font). You build the UI once —
  at a tablet, say — and watch the real components bring it down to a phone beside you. The frames
  are device *properties* (a width, a height, a density written over the design's environment),
  never a picture of a handset; see
  [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](UI_BUILDER_CANVAS_FRAMES_VARIANTS.md).

  For that to mean anything, **each frame has to be its own window**. An adaptive component asked
  `currentWindowAdaptiveInfo()` would be told about the browser holding the whole row, and every
  frame would expand or collapse together — disproving nothing. So the scaffold computes its size
  class from its own constraints, which inside `ConstrainedFramePane` are the device's width and
  height at the device's density. The posture stays the real one: a hinge is hardware, not a frame.

  The **generated Kotlin does the same**, and there it is not about frames: a scaffold under a
  `width`, a `widthIn` or any narrower parent inside a wide window would be told about the window,
  ask for two partitions, and disagree with the preview pane that measured its real bounds. Whether
  a rung-2/rung-3 disagreement means anything depends on the two rungs asking the same question.

There is no selection overlay, so taps reach the controls: a screen wired to react can be made to
react. It is still not editable — a design has one document, and offering a coordinate space per
frame for one shared outcome is what makes multi-variant editors confusing.

And it costs **nothing**. No compile, no daemon, no seat. That is why it can be switched on
mid-thought, and the reason the rung that does cost a round trip is a separate choice somebody makes
on purpose.

### Where "real" stops

"Real components in the browser" is bounded by what a Wasm build can link. Compose Multiplatform
Material 3 links; `androidx.wear.compose:compose-material3` is an Android AAR and never will. On a
catalog like `wear-m3`, rung 2 **cannot** be real, and the honest move is for the catalog to say so
rather than for the pane to imply otherwise — which is exactly what
[`UiBuilderPreviewSurfaces`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/UiBuilderPreviewSurfaces.kt)'s
`wasm` claim is for. Where it reads `APPROXIMATE`, rungs 1 and 2 collapse into each other and rung 3
is the only honest picture. That does not make Native the default: the approximate browser view is
still the direct editing surface and the free place to compare variants; the catalog's surface claim
explains its limit where Native is chosen.

## 3. Native is real source on the real platform

Rung 3 does not render the document at all. It generates the design's **Kotlin**, compiles it, and
runs it on the target platform's own toolkit — Robolectric-backed Android where the catalog declares
`native.backend = android`, the desktop daemon otherwise.

That is the full-fidelity mode, and it is not the only one the pane has. What you are looking at is
one of three, and the pane's caption says which:

| mode | what it is | when |
| --- | --- | --- |
| **compiled, live** | the generated Kotlin, compiled on the host, streamed from a held session with taps dispatched into it | a host with a compile lane and Stage-2 redemption |
| **compiled, still** | the same compile, one frame, with node boxes so a click selects a layer | no live backend for the design's mode, or a full live-seat budget |
| **played document** | the *document* exported to Remote Compose and played by the RC player **in this browser** | a host with no compile lane at all |

Only the first two are "real platform". The third is the honest best available where nothing can
compile — it is the design's own exported bytes rather than a re-render, but it runs where rungs 1
and 2 run, so read it as a rung 2½ rather than as a rung 3. `UI_BUILDER_REMOTE_COMPOSE.md` has the
lanes in full.

Two consequences worth stating about the compiled modes:

- **It is the only rung that can be wrong about the export.** Rungs 1 and 2 draw the document; rung 3
  draws the *generated source*. A design the generator refuses has no native render, and the reasons
  are the actionable half — which is why the pane lists them rather than showing an empty box.
- **It is a live preview, not a live edit** — in the live mode. The frame is streamed from a held
  session and taps are dispatched into the real composition, so you use the screen rather than the
  picture of it. An edit re-generates and re-compiles; it does not reach the running session the way
  a keystroke reaches rungs 1 and 2. In the still mode there is nothing to interact with at all, and
  a click selects a layer instead.

**Single frame**, because each one is a daemon: multiplying frames here multiplies JVMs, live seats
and boot time. The frame is one the author chooses from the Preview's variants; the device question
was already answered a rung down, for free.

## What the ladder buys you

A disagreement between two rungs is information, and the ladder says what kind:

| Disagreement | What it means |
| --- | --- |
| 1 vs 2 | authoring presentation — an unrolled list, an overlay, a stand-in component. Usually expected. |
| 2 vs 3 | **the export.** The document draws one thing and the generated source another; one of them is a bug. |
| 2 across frames | the design's own adaptive behaviour, which is what rung 2 exists to show. |

And it says where to look: *"it looks wrong in the editor"* is rung 1 doing its job until rung 2 says
otherwise; *"it looks wrong in the preview"* is a design bug; *"it looks wrong natively"* is an export
bug.

## Sanctioned substitutes

The substitute inventory is deliberately short and executable in `PreviewFidelityInventoryTest`.
Anything not listed here is a fidelity defect.

| Component | Visual editor | Preview | Generated Native |
| --- | --- | --- | --- |
| lazy lists, grids, Scaffold, TransformingLazyColumn | unrolled authoring layout | real component | real component |
| `layout/supporting-pane-scaffold` | unfolded authoring scaffold | real adaptive scaffold | real adaptive scaffold |
| `layout/horizontal-carousel` | `LazyRow` authoring adapter | `HorizontalUncontainedCarousel` | `HorizontalUncontainedCarousel` |
| `m3/search-bar` / `m3/search-input-field` | inline `Surface` / `BasicTextField` adapters | `SearchBar` / `SearchBarDefaults.InputField` | the same real APIs |
| `m3/dialog` | inline dialog surface, so its children remain editable | `AlertDialog` | `AlertDialog` |
| `m3/horizontal-floating-toolbar` | compatibility adapter | compatibility adapter | compatibility adapter, declared in export provenance |
| a component unavailable to Wasm | named `NativeOnlyPlaceholder` | named placeholder and an approximate/none catalog claim | real platform component |

The floating toolbar is the sole Material 3 exception on the current dependency floor. Compose
Multiplatform 1.12 resolves Material 3 1.9.0, which does not publish
`HorizontalFloatingToolbar`. Its exporter warning and `component-adapter:<node>:<component>`
provenance entry prevent a compiled result from silently claiming API parity. Remove that row — and
the helper — when the pinned Material dependency supplies the real API.

## Where the implementation stands

**Holds.** The editor's unrolled extent versus the preview's bounded composition is real and
implemented — `CanvasExtentLayout` unrolls, `ConstrainedFramePane` does not, and the KDoc on the
latter already says it is "the one that answers what a device actually shows". Single / multiple /
single holds: `PinnedDesignCanvas` takes no variant list at all, the preview pane draws every device
and axis, and the native pane draws one frame. The native rung goes through the generator, the
compile lane and the platform daemon, and streams a live session.

**Holds for the adaptive scaffold, as of the change that added this paragraph.**
`layout/supporting-pane-scaffold` is `androidx.compose.material3.adaptive`'s own
`SupportingPaneScaffold` in the preview pane and in the Kotlin the capability exporter generates,
where there used to be two hand-rolled helpers with two different thresholds. `layoutMode` stopped
being a width comparison and became a `PaneScaffoldDirective`: every spelling but `singlePane` lets
the library decide, and `singlePane` pins `maxHorizontalPartitions` to 1.

The **record-driven** projection still refuses the property, and that refusal is now narrower rather
than stale. Mapping a mode onto a directive is a computation at the call site; that projection emits
a property by writing its value as an argument to a recorded member, and there is no member for it to
be an argument of — so a hand-written emitter can do it and a record cannot
([`ScreenDocumentProjection.VARIANT_PROPERTIES`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/export/ScreenDocumentProjection.kt)).
The component has no record there in any case, because its panes are not plain composable slots.

The canvas keeps its stand-in, and that is the ladder working rather than a leftover: the real
scaffold cannot be measured against an unbounded height (`Size(1280 x 2147483647) is out of range`),
which is exactly how the authoring canvas measures so a list can be edited past its fold. A 1-vs-2
disagreement about pane count is therefore expected, and it is the first row of the table above.

**Holds for every API on the dependency floor.** The renderer's explicit strategy boundary keeps
carousel, search and dialog stand-ins in the unrolled editor and selects their real Material APIs in
the bounded pane. The capability exporter emits those same real calls; it no longer writes
`BuilderHorizontalCarousel`, `BuilderSearchBar`, `BuilderSearchInputField`, or
`BuilderDialogSurface` lookalikes. The inventory above records the one dependency-floor exception
instead of allowing it to disappear inside an otherwise authoritative generated file.

## See also

- [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](UI_BUILDER_CANVAS_FRAMES_VARIANTS.md) — the frame, the
  devices, and why a variant is a pane rather than a document.
- [`UI_BUILDER_WEAR_SCREEN.md`](UI_BUILDER_WEAR_SCREEN.md) — the catalog where rung 2 cannot be real,
  and why a borrowed component may not be faked.
- [`UI_BUILDER_REMOTE_COMPOSE.md`](UI_BUILDER_REMOTE_COMPOSE.md) — the native rung's lanes, including
  the played-document fallback where there is no compile lane.
- [`renders/ui-builder-native-live`](https://github.com/yschimke/compose-preview-server/tree/e26ab4f6e345e5cc2d3f8fea6156396a8ea5fe60/renders/ui-builder-native-live) — rung 3 streaming, and
  what its fixtures do and do not claim.
