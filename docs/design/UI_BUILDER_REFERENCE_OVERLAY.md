# The UI-builder reference overlay

A picture to build against, kept with the design: a Figma export, a screenshot of a shipped screen,
a snapshot of the design as it stands. Plus the markup that turns one of those into the next one —
draw on it, erase part of it, drop a component into place, and flatten the result into the
reference for the next round.

This document is the *why*. The rules it states are load-bearing; the code cites it rather than
restating them.

## The line: pixels here, structure there

The builder has two halves and they answer different questions.

| | The **document** | The **reference** |
| --- | --- | --- |
| What it holds | Nodes, properties, bindings | Pixels |
| Validated against | The catalog | Nothing |
| Exports to | Compose, SVG, PNG | Nothing, ever |
| Collaborative | Yes — revisioned, replayed, undoable | No — one operator's scaffolding |
| Where it lives | `DesignDocumentV1`, on the collaboration wire | `references/<digest>.json`, beside the state |

Keeping those invariants apart is what keeps each half simple. Everything in the document is
exportable Compose that a catalog vouched for; nothing in the reference is anybody's truth. A layer
that was a bit of both would be a design you cannot export and a reference you cannot trust.

So the reference is **not** in the design document, for three reasons in order of weight:

1. **It is not part of the design.** A mock pasted in to line a screen up must never become an
   `m3/image` node that ships in the generated Kotlin.
2. **The wire cannot carry it.** `DesignMutationV1` is a closed set with no asset mutation, so
   `assets` cannot be written after `createDesign` without releasing `ui-builder-protocol` — to
   store something point 1 says should not be in the document.
3. **It must not cost the document anything.** The document is replayed, hashed, diffed for catalog
   upgrades and pushed to every subscriber on every edit. A multi-megabyte PNG would ride through
   all of that, every time, to be drawn by one person's editor.

### The door between them is one-way, and both directions are built

**Design → reference: rasterise.** Two routes, one rule — nothing live ever enters the reference
layer.

- **Snapshot** renders the whole design through its own PNG export and attaches the pixels.
- **Component…** composes one catalog component through the *same renderer that draws the document*
  — same catalog defaults, same theme, same density — captures it to a graphics layer, trims the
  transparent margins, and places the result as a piece.
  ([`ReferenceComponentCapture`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceComponentCapture.kt).)

The specimen is built by running the editor's *own insertion path* on a throwaway one-box document,
rather than by assembling nodes by hand. That is load-bearing: a picture of something the editor
could not actually insert would be a lie the operator only discovers when they try to build it.

**Reference → design: build it, and no agent is needed for the case that matters.**
[`PromoteReferencePiece`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/editor/UiBuilderEditorState.kt)
turns a captured piece into the real component:

1. The piece names a catalog component — [`ReferencePiece.componentId`], recorded at capture.
2. The slot comes from a **hit test against the layout the canvas actually produced**: the deepest
   slot in the inspection snapshot that contains the piece's centre *and* whose owning component
   accepts this one. Position rather than selection, because the piece's whole claim is where it is.
3. The insertion is then the ordinary one — the same `insertAt` a catalog drag performs, catalog
   defaults and required slots and all — after which the picture is removed, because the real thing
   is standing where it was.

Deterministic end to end. **An agent is only needed for a piece with no provenance** — a screenshot
region, a Figma export — where "which component is this?" is a genuine judgement. The reducer
refuses that case rather than guessing, and the panel offers no button for it.

The consequence worth stating: the moment a piece's *properties* become editable, the piece must
record them too, or promoting will silently rebuild the defaults. Today a specimen is captured from
defaults and nothing edits it, so the id alone is lossless.

## The loop it exists for

1. **Snapshot** the design, or **import** a Figma export, or **paste** one (Figma's "copy as PNG"
   puts a frame straight on the clipboard, and the editor catches it).
2. **Compare** — overlay at an opacity, difference (matching pixels go black), a split wipe, or the
   SVG's own layout boxes.
3. **Mark up** — draw, box, rounded box, ellipse, arrow, a label, an image placeholder, and the
   **erase** brush, which paints in the screen's own background colour.
4. **Place** a picture, or **capture a component**, where it should go, and drag it into position.
5. **Build for real** — a captured component becomes a real node in the slot under it.
6. **Flatten** — bake the picture, the pieces and the marks into one reference, and go round again.

The erase brush is what makes a *shipped* screen editable. A screenshot is one flat picture: there
is no card to delete. Painting a region in the colour the screen already is removes it, and the hole
left behind is exactly the space a real component can be built into and compared against its
surroundings. The palette carries the design's own `background` and `surface` colours for that
reason — a hole in nearly the right colour is worse than no hole.

## What the picture is, and when comparing it means anything

A picture arrives as pixels with no unit, and every comparison after that is only as good as the
answer to "how big is this in dp?". [`ReferenceFacts`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceFacts.kt)
answers it once, for the panel, the fitting and the matcher alike:

- **Density** is *declared* where the picture says so — an `@2x` suffix, which every design tool and
  asset catalog writes, and the Figma fetch names its file that way on purpose — otherwise
  *inferred* from the frame width for anything screen-shaped (snapped to a real bucket: 2.6247 is a
  Pixel's 2.625), otherwise *assumed* to be the design's own environment density.
- **Kind** follows from that: a **Screen** (the frame's shape), a **Tall screen** (as wide, taller —
  system bars or a scrolled capture), a **Region** (smaller than the frame: a component crop), or a
  **Different shape**.
- **Fit** — `Contain` (the historic behaviour), `Width` (pinned to the top) or `Actual` (its own
  dp size from the top-left) — is chosen from the kind when a picture is attached and can be
  changed in the panel. It is persisted beside the other settings; a host whose stored shape
  predates the field drops it and reads back `Contain`, which is what every older record was drawn
  with.

**When pixels may be compared** is then one rule, `pixelComparable(fit)`: only when one dp of the
picture lands on one dp of the frame. Overlay and Split are judged by eye and are always offered;
Difference, *Measure differences* and a pixel search for a layer are offered only when the rule
holds, and the panel says why when it does not ("a 328 × 56 dp piece, not a screen: stretched over
the frame every pixel differs").

## Measuring, and building from the measurement

Both measurements photograph the design off screen through the canvas's own renderer
([`DesignCapture`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceMeasure.kt)),
resample it and the reference — placed exactly as the overlay places it — onto one grid of one or
two samples per dp, and work on plain ARGB rasters
([`ReferenceRaster`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceRaster.kt)),
so they run identically in a browser, on the desktop and in a JVM test.

- **Differences** reports the share of compared pixels that differ and up to eight regions,
  found on an 8 dp cell grid so a glyph half a pixel over is one region rather than a hundred
  pixels. Each region names the smallest layer containing its centre, and clicking it selects
  that layer.
- **Match a layer** lines the selected layer up with the reference from the best evidence there
  is ([`matchLayer`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceAlignment.kt)):
  a **box the operator drew** over it (their word; for text narrowed to the ink inside it), then
  an **SVG layout box** that is plainly the same thing, then a **pixel search** for the layer's
  own rendering near where it sits — at a range of scales for text, which is how a font size is
  read off a mock. Text is compared by its *ink* on both sides, because a node's box carries line
  height a mock's glyphs do not.

The match becomes an edit with **Apply**:
[`AlignNodeToReference`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/editor/UiBuilderEditorEvents.kt)
moves the layer with padding (the leading edge grows, the trailing edge gives back what it has, so
the box keeps its size), spills only the part padding cannot express onto an `offset`, sets a fixed
size where the evidence stated one, and writes `fontSizeSp` — all in one batch, so it is one undo
step and is refused whole when any part of it cannot be written. This is the one place the
reference changes the document, and it does so only through an ordinary, catalog-validated
operation the operator pressed a button for.

### One engine, for the editor and for agents

Everything that measures — placement ([`referencePlacement`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceGeometry.kt)),
the facts, the rasters, the diff, the layer match, the padding/offset chain and the entry points
[`compareDifferences` and `compareLayer`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceComparison.kt)
— lives in `:ui-builder-export`, with no Compose type in it. The editor feeds it a photograph of its
canvas; the serve host feeds it the design's render to answer an agent's MCP call. One result for
both, so an agent told "move right 12 dp" and a person shown it on the canvas are reading the same
arithmetic.

## Links: Figma frames and image URLs

[`parseReferenceUrl`](../../ui-builder-export/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceUrl.kt)
recognises Figma file, design, prototype and branch links (with their `node-id`) and plain https
image links; fetching is the host's decision.

- The **desktop and IntelliJ hosts** run as the operator, so they may use *the operator's* Figma
  token: with `FIGMA_TOKEN` set they render the frame through Figma's images API at 2× and attach it
  as `Figma 12-34@2x.png`, link kept as provenance. Without one, the refusal names the variable and
  the paste route. Image links are fetched directly, capped at the reference size limit.
- The **web editor** fetches image links from the browser, which works where the site allows a
  cross-origin read. It does not fetch Figma frames: the serve host holds no design-tool
  credential and the page does not ask for one, so a Figma link is answered with the paste route.

The desktop host keeps each design's reference in `<storage>/references/<digest>.json`, named by a
digest of the design's file path or workspace, never beside a design file somebody may share.

## What is stored, and where

One file per design under `<ui-builder-state>/references/`, named by the digest of the design id (a
design id is caller-supplied text and never becomes a path segment). Beside the state file rather
than inside it: that file is one blob rewritten on every accepted operation, and folding references
into it would rewrite every reference on the host on every keystroke.

The overlay's *shape* is published as `DesignReferenceV1` and its parts in `ui-builder-protocol`,
with the links record's and the comment board's — a wire shape belongs where wire shapes live, and
this one is a response body, an MCP payload and a file on disk at once. `StoredReference` here is
an alias. The JSON is unchanged by the move, and the clamping stayed here, because the contracts
module is shape and never behaviour.

Routes, all gated twice — the route capability decides whether this caller may use the builder at
all, and then the design's own access control decides what this actor may do to *this* design: its
READ action to see what the design reproduces, its WRITE action to change it. So an actor shared in
as a viewer is refused with a 403 even holding `ui-builder-write` on the host, because a capability
is permission to use the door and not permission to re-aim somebody else's design. Both gates, and
why the comment board deliberately stops at READ, are in
[`UI_BUILDER_SIDECAR_ACCESS.md`](UI_BUILDER_SIDECAR_ACCESS.md):

| Route | Capability | Carries |
| --- | --- | --- |
| `GET /api/ui-builder/v1/designs/{id}/reference` | READ | the whole record |
| `PUT …/reference` | WRITE | pictures, settings, pieces, marks |
| `PUT …/reference/settings` | WRITE | settings, pieces, marks — no pictures |
| `DELETE …/reference` | WRITE | — |

Two write routes because dragging an opacity slider must not re-upload several megabytes per frame.
The editor sends the cheap one whenever no picture changed.

## Accepting an SVG

PNG, JPEG and WebP are sniffed and admitted. SVG is admitted too, where
[`ServeImageFormats`](https://github.com/yschimke/compose-preview-server/blob/e26ab4f6e345e5cc2d3f8fea6156396a8ea5fe60/server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeImageFormats.kt)
deliberately refuses it — and the difference is real rather than an inconsistency. That lane hands
bytes back from this origin as a *document* anyone with the link can navigate to. A reference is
returned base64-encoded inside a JSON body and drawn into a Skia canvas by the editor that asked for
it: there is no page for active content to run on, Skia executes no script, and it is given no
resource provider.

It is checked anyway — no `<script>`, no `<foreignObject>`, no `on…=` handler, no reference that
leaves the file — because "nothing navigates to it today" is not a boundary and the bytes outlive
today's renderer. The check exists twice, and the two copies are different on purpose:

- The **editor's** copy runs the export lane's own conservative parser, so a bad paste is refused
  before a round trip.
- The **host's** copy is a textual scan, because `:server` cannot depend on the Compose module and a
  second XML parser written for a trust boundary is a liability. A textual scan can only err toward
  refusing.

The host's copy is the authority. Keep the editor's no stricter, or it will refuse imports the host
would have kept.

## Layout boxes

An SVG exported from a design tool carries the frames it was laid out with — Figma emits one `<rect>`
per frame and names it. Those rectangles are what a Compose layout is worth comparing against: "is
this card the right width" is answered by the box, where the fill and the type it is painted with
only get in the way of asking.

[`extractSvgLayoutBoxes`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceLayoutBoxes.kt)
reuses `parseStrictSvg` rather than adding a second XML reader, and answers in fractions of the
SVG's viewport so the drawing code needs nothing from the parse. Rotation and skew are **dropped
rather than approximated**: a rotated card's bounding box is not the card, and a guide that lies is
worse than a guide that is absent.

`StrictSvgParseResult` grew a `structure` field for this. The export lane asks "may these bytes be
published as our own artefact", where one unvouched raster reference is a no; a reader that only
wants the geometry of a file the operator supplied asks whether the markup could be read at all.
Two questions, one parse, separate answers.

## Testing it

- [`ReferenceLayoutBoxesTest`](../../ui-builder/src/commonTest/kotlin/ee/schimke/composeai/uibuilder/ReferenceLayoutBoxesTest.kt) — geometry, transforms, and what is dropped.
- [`ReferenceImportTest`](../../ui-builder/src/commonTest/kotlin/ee/schimke/composeai/uibuilder/ReferenceImportTest.kt) — what may be attached.
- [`ReferenceOverlayStateTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/ReferenceOverlayStateTest.kt) — the reducer, and **every case asserts the document did not move**. That is the invariant this feature lives or dies by.
- [`ServeUiBuilderReferenceStoreTest`](https://github.com/yschimke/compose-preview-server/blob/e26ab4f6e345e5cc2d3f8fea6156396a8ea5fe60/server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderReferenceStoreTest.kt) — storage, refusals, clamping, and that a design id never becomes a path.
- [`ReferenceFactsTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/ReferenceFactsTest.kt) — density, kind and when pixels may be compared.
- [`ReferenceMatchingTest`](../../ui-builder-export/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceMatchingTest.kt) — the diff, ink boxes, and finding a moved or resized label, on pictures built from rectangles.
- [`ReferenceAlignToReferenceTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/ReferenceAlignToReferenceTest.kt) — applying a match is one undoable batch, refused whole, and never touches the reference.
- [`ReferenceUrlTest`](../../ui-builder-export/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/reference/ReferenceUrlTest.kt) and [`JvmReferenceHostTest`](../../ui-builder-host-jvm/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/host/JvmReferenceHostTest.kt) — links, sniffing, and the desktop store.
- [`ReferencePiecePromotionTest`](../../ui-builder/src/jvmTest/kotlin/ee/schimke/composeai/uibuilder/ReferencePiecePromotionTest.kt) — the crossing back: a captured piece builds the node a catalog insertion would, a piece with no provenance is refused rather than guessed at, and the deepest accepting slot under the point wins.
