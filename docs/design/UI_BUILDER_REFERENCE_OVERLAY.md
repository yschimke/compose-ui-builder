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

### The door between them is one-way, cheap in one direction and deliberate in the other

- **Design → reference: rasterise.** Snapshot renders the design through its own PNG export and
  attaches the pixels. A live preview dropped onto the reference becomes an image *at the moment of
  the drop*; no live component ever lives in the reference layer.
- **Reference → design: build it.** Turning a reference into structure is real work — real nodes,
  validated against the catalog — done by a person or by an agent asked to do it. It never happens
  as a side effect.

[`ReferencePiece.componentId`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/UiBuilderReference.kt)
is the hinge that keeps the second direction possible: a piece rasterised out of a live preview
records what it was a picture of, so "build this for real" is a lookup rather than a guess.

## The loop it exists for

1. **Snapshot** the design, or **import** a Figma export, or **paste** one (Figma's "copy as PNG"
   puts a frame straight on the clipboard, and the editor catches it).
2. **Compare** — overlay at an opacity, difference (matching pixels go black), a split wipe, or the
   SVG's own layout boxes.
3. **Mark up** — draw, box, rounded box, ellipse, arrow, a label, an image placeholder, and the
   **erase** brush, which paints in the screen's own background colour.
4. **Place** a picture where it should go, and drag it into position.
5. **Flatten** — bake the picture, the pieces and the marks into one reference, and go round again.

The erase brush is what makes a *shipped* screen editable. A screenshot is one flat picture: there
is no card to delete. Painting a region in the colour the screen already is removes it, and the hole
left behind is exactly the space a real component can be built into and compared against its
surroundings. The palette carries the design's own `background` and `surface` colours for that
reason — a hole in nearly the right colour is worse than no hole.

## What is stored, and where

One file per design under `<ui-builder-state>/references/`, named by the digest of the design id (a
design id is caller-supplied text and never becomes a path segment). Beside the state file rather
than inside it: that file is one blob rewritten on every accepted operation, and folding references
into it would rewrite every reference on the host on every keystroke.

Routes, all gated twice — the route capability decides whether this caller may use the builder at
all, and then the design is read *through the service as that actor*, so the design's own access
control decides whether there is anything here to attach to:

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
[`ServeImageFormats`](../../server/src/main/kotlin/ee/schimke/composeai/cli/serve/ServeImageFormats.kt)
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

[`extractSvgLayoutBoxes`](../../ui-builder/src/commonMain/kotlin/ee/schimke/composeai/uibuilder/ReferenceLayoutBoxes.kt)
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
- [`ServeUiBuilderReferenceStoreTest`](../../server/src/test/kotlin/ee/schimke/composeai/cli/serve/ServeUiBuilderReferenceStoreTest.kt) — storage, refusals, clamping, and that a design id never becomes a path.
