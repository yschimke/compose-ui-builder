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

## 1. The visual editor is the one that is allowed to lie

This is the load-bearing asymmetry of the whole workspace, and it is deliberate rather than a gap.

An editing surface has a job that a faithful one cannot do: it has to show you the thing you are
editing, including the parts a device would not show. The clearest case is a scrolling column. A
`LazyColumn` on a 914 dp phone shows you rows one to four; the author is working on rows nine to
twelve as much as on the first four. So the canvas draws the **extent** — the frame's width, the
content's full height, unrolled — and that is the surface edits land on
([`CanvasExtentLayout`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/CanvasExtentLayout.kt)).
It is not what the device shows. That is the point.

The same licence covers everything else the canvas puts on top of the design: the selection overlay,
drop targets, the hover editor, comment pins, collaborator cursors. None of it is in the design, and
all of it is in the way of using the design.

Components on this rung may be **adapters or placeholders**. What matters on rung 1 is that a node is
**there**, is **selectable** and **takes a drop** — not that it is the component the export names.

This is not a licence to fabricate. `AGENTS.md` forbids hand-assembling a lookalike to stand in for a
library the canvas cannot link, and the renderer keeps that rule with two different shapes:

- an **adapter**, where the canvas has a real component that carries the same contract —
  `CompatibleHorizontalCarousel` over a `LazyRow`, because Material's uncontained carousel is not on
  the dependency floor. The node draws, and what it draws is a real component;
- a **named placeholder** ([`NativeOnlyPlaceholder`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt)),
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
  ([`ConstrainedFramePane`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderEditor.kt)).
  A design that overflows here overflows on the device.
- *Does it adapt?* This is the rung that earns the **multiple frames**: every device the design
  claims in `exportDevices`, plus the unstored axes (dark, RTL, large font). You build the UI once —
  at a tablet, say — and watch the real components bring it down to a phone beside you. The frames
  are device *properties* (a width, a height, a density written over the design's environment),
  never a picture of a handset; see
  [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](UI_BUILDER_CANVAS_FRAMES_VARIANTS.md).

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
[`UiBuilderPreviewSurfaces`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderPreviewSurfaces.kt)'s
`wasm` claim is for. Where it reads `APPROXIMATE`, rungs 1 and 2 collapse into each other and rung 3
is the only honest picture; the pane chooser and the preview pane's own caption both repeat the
catalog's sentence about why.

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
and boot time. The device question was already answered a rung down, for free.

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

## Where the implementation is against this

The ladder is the design. Two rungs of it hold today and one does not, and saying which is the point
of writing it down.

**Holds.** The editor's unrolled extent versus the preview's bounded composition is real and
implemented — `CanvasExtentLayout` unrolls, `ConstrainedFramePane` does not, and the KDoc on the
latter already says it is "the one that answers what a device actually shows". Single / multiple /
single holds: `PinnedDesignCanvas` takes no variant list at all, the preview pane draws every device
and axis, and the native pane draws one frame. The native rung goes through the generator, the
compile lane and the platform daemon, and streams a live session.

**Does not hold yet: rung 2's components are rung 1's.** Both panes go through the same
[`UiBuilderRenderer`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderRenderer.kt),
so the preview inherits every stand-in the editor uses. The known ones:

- `layout/supporting-pane-scaffold` → `DeterministicSupportingPaneScaffold`, a `BoxWithConstraints`
  with its own threshold rather than `androidx.compose.material3.adaptive`'s `SupportingPaneScaffold`.
  The Kotlin export emits a *second* hand-rolled helper with a different threshold again, so rung 2
  and rung 3 can disagree about when a design expands — which by the table above reads as an export
  bug and is really a missing dependency.
- `layout/horizontal-carousel` → `CompatibleHorizontalCarousel`; Material's uncontained carousel is
  not on the dependency floor.
- `CompatibleFloatingToolbar`, and the `wear-m3` Material 3 lookalikes — the last of which is the
  bounded case above and stays a stand-in by construction.

Closing this is the direction, not a defect list to clear before the ladder is true: link the real
libraries into the renderer where they are KMP-capable, emit the real components from the exporter,
and let properties the real component derives from the window (`layoutMode`) stop being authored.
Until then, rung 2 tells the truth about **layout and fit** and inherits rung 1's components, and the
preview pane's caption is the place that has to keep saying so.

## See also

- [`UI_BUILDER_CANVAS_FRAMES_VARIANTS.md`](UI_BUILDER_CANVAS_FRAMES_VARIANTS.md) — the frame, the
  devices, and why a variant is a pane rather than a document.
- [`UI_BUILDER_WEAR_SCREEN.md`](UI_BUILDER_WEAR_SCREEN.md) — the catalog where rung 2 cannot be real,
  and why a borrowed component may not be faked.
- [`UI_BUILDER_REMOTE_COMPOSE.md`](UI_BUILDER_REMOTE_COMPOSE.md) — the native rung's lanes, including
  the played-document fallback where there is no compile lane.
- [`renders/ui-builder-native-live`](../../renders/ui-builder-native-live) — rung 3 streaming, and
  what its fixtures do and do not claim.
